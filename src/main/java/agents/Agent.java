package agents;

import agents.mind.Disposition;
import agents.mind.IntentExecutor;
import agents.mind.Policy;
import agents.protocol.ClientPackets;
import agents.net.LoginFlow.InWorld;
import agents.memory.Belief;
import agents.percept.Observation;
import agents.percept.Perceiver;
import agents.social.Claim;
import agents.social.Conversation;
import agents.world.WorldModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One agent, living its own loop on its own thread.
 *
 * Perceive, remember, decide, act - and write down why, every time round. Agents share
 * nothing: no memory, no world model, no coordination. Anything one knows that another does
 * not has to travel through the game, which is what makes knowledge spreading between them
 * an observation rather than an assumption.
 */
public class Agent implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    /**
     * How long between decisions. Fast enough to look alive, slow enough that the server sees
     * a plausible client and a later LLM policy is not asked to think ten times a second.
     */
    private static final Duration TICK = Duration.ofMillis(600);

    private final InWorld connection;
    private final Mind mind;
    private final Policy policy;
    private final Perceiver perceiver = new Perceiver();
    private final WorldModel world = new WorldModel();
    private final IntentExecutor executor;
    private volatile boolean running = true;

    private final Disposition disposition;
    private int steps;
    private int nextToShare;

    public Agent(InWorld connection, Mind mind, Policy policy, Disposition disposition) {
        this.connection = connection;
        this.mind = mind;
        this.policy = policy;
        this.disposition = disposition;
        this.executor = new IntentExecutor(connection.session());
    }

    @Override
    public void run() {
        String name = mind.name();
        log.info("{} is awake, policy {}", name, policy.name());

        try {
            while (running && connection.session().isConnected()) {
                step();
                Thread.sleep(TICK.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // One agent falling over should not take the run with it.
            log.error("{} stopped unexpectedly", name, e);
        } finally {
            log.info("{} is done: {} episodes, {} beliefs", name,
                    mind.episodic().size(), mind.semantic().size());
        }
    }

    private void step() {
        List<Observation> observations = perceiver.perceive(connection.inbox());
        for (Observation observation : observations) {
            world.update(observation);
            mind.take(observation);
            acknowledgeArrival(observation);
            answerNpc(observation);
            converse(observation);
        }

        if (steps++ % disposition.shareInterval() == disposition.shareInterval() - 1) {
            share();
        }

        long tick = perceiver.currentTick();
        Policy.Decision decision = policy.decide(mind, world, tick);

        mind.decided(tick, decision.goal(), decision.intent().name(),
                decision.intent().detail(), decision.consultedBeliefs());

        executor.execute(decision.intent(), world);
    }

    /**
     * Keeps an NPC conversation going without understanding a word of it.
     *
     * The dialogue style says what kind of answer is wanted, which is enough: say yes to
     * anything that asks, acknowledge anything that does not. An agent finds out what it
     * agreed to by watching what changes afterwards.
     */
    private void answerNpc(Observation observation) {
        if (!(observation instanceof Observation.DialogueShown dialogue)) {
            return;
        }
        connection.session().send(ClientPackets.npcTalkMore(
                (byte) dialogue.style(), NPC_YES_OR_NEXT, NO_SELECTION));
    }

    /** Action 1 means yes, or next, depending on what was asked. */
    private static final byte NPC_YES_OR_NEXT = 1;
    private static final int NO_SELECTION = -1;

    /**
     * Answers questions put to it, and picks up claims other agents make.
     *
     * A question can arrive privately, or in map chat addressed by name - "Agent1: why" -
     * so a person can talk to one agent without whispering.
     */
    private void converse(Observation observation) {
        String text;
        String replyTo;

        if (observation instanceof Observation.WhisperHeard whisper) {
            // A claim is a claim whichever way it arrives, and whispering one is how a
            // person tells an agent something without shouting it at the whole map.
            Optional<Claim> told = Claim.parse(whisper.text());
            if (told.isPresent()) {
                adopt(told.get());
                connection.session().send(ClientPackets.whisper(whisper.speakerName(),
                        "noted, though I have not seen that myself"));
                return;
            }
            text = whisper.text();
            replyTo = whisper.speakerName();
        } else if (observation instanceof Observation.ChatHeard chat) {
            if (chat.speakerId() == world.characterId()) {
                return;     // our own voice coming back off the map
            }
            Claim.parse(chat.text()).ifPresent(this::adopt);
            text = addressedToMe(chat.text());
            replyTo = null;
            if (text == null) {
                return;
            }
        } else {
            return;
        }

        Optional<String> answer = Conversation.answer(text, mind, world);
        answer.ifPresent(reply -> {
            if (replyTo != null) {
                connection.session().send(ClientPackets.whisper(replyTo, reply));
            } else {
                connection.session().send(ClientPackets.chat(reply, false));
            }
        });
    }

    /** "Agent1: why" is for Agent1. Returns the question, or null if it was not for us. */
    private String addressedToMe(String text) {
        String prefix = mind.name() + ":";
        return text.regionMatches(true, 0, prefix, 0, prefix.length())
                ? text.substring(prefix.length()).trim()
                : null;
    }

    /**
     * Takes another agent's word for something - at hearsay confidence, grounded in the
     * episode of hearing it, so the replay shows it as second-hand until the agent sees it
     * for itself.
     */
    private void adopt(Claim claim) {
        mind.hear(claim.subject(), claim.predicate(), claim.object(), perceiver.currentTick());
    }

    /**
     * Says something it is sure of, in a form another agent can pick up.
     *
     * Rotates through what it knows rather than repeating its single best fact. Always
     * announcing the same thing spreads one belief and nothing else, which is not how
     * knowledge gets around.
     */
    private void share() {
        List<Belief> worthSaying = mind.semantic().liveBeliefs().stream()
                .filter(b -> b.provenance() == Belief.Provenance.FIRST_HAND)
                .filter(b -> !b.subject().equals("self"))
                // "player:4 said ..." is a fact about a speaker, not about the world, and
                // announcing one wraps a claim inside a claim - which then gets announced
                // again. A run produced "player:6 said !know player:4 said !know ..." before
                // this filter existed.
                .filter(b -> !b.predicate().equals("said"))
                .sorted(Comparator.comparingDouble(Belief::confidence).reversed())
                .toList();
        if (worthSaying.isEmpty()) {
            return;
        }
        Belief belief = worthSaying.get(nextToShare++ % worthSaying.size());
        String claim = Claim.announce(belief);

        connection.session().send(ClientPackets.chat(claim, false));

        // Map chat only carries as far as the map, and agents that have diverged are by
        // definition somewhere else. Anyone it has met is reachable by name wherever they
        // are, which is how news actually travels between people who have split up.
        acquaintances().forEach(name ->
                connection.session().send(ClientPackets.whisper(name, claim)));
    }

    /** Names of players it has seen, from its own beliefs. */
    private List<String> acquaintances() {
        return mind.semantic().liveBeliefs().stream()
                .filter(b -> b.predicate().equals("named") && b.subject().startsWith("player:"))
                .map(Belief::object)
                .distinct()
                .toList();
    }

    /**
     * Confirms we have finished loading whatever map we were sent to. Without this the
     * server leaves the character flagged mid-transition and refuses every later map change.
     */
    private void acknowledgeArrival(Observation observation) {
        if (observation instanceof Observation.MapEntered
                || observation instanceof Observation.SelfDescribed) {
            connection.session().send(ClientPackets.mapTransitionComplete());
        }
    }

    public void stop() {
        running = false;
    }

    public Mind mind() {
        return mind;
    }

    public WorldModel world() {
        return world;
    }

    public Perceiver perceiver() {
        return perceiver;
    }
}
