package agents;

import agents.mind.DialogueReader;
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
import agents.social.Voice;
import agents.world.WorldModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final Voice voice;
    private final DialogueReader dialogueReader;

    /**
     * Where the thinking about an NPC's words happens, so it does not happen on the tick.
     *
     * A local model takes around fifteen seconds, and an agent frozen mid-conversation for
     * fifteen seconds is worse to watch than one that answers thoughtlessly.
     */
    private final ExecutorService reading =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dialogue");
                t.setDaemon(true);
                return t;
            });

    private PendingDialogue pendingDialogue;
    private volatile boolean running = true;

    /** An NPC's words, out with the model, with the style byte needed to answer them. */
    private record PendingDialogue(byte style, long askedAtMillis,
                                   CompletableFuture<java.util.Optional<DialogueReader.Reply>> answer) {
    }

    /** After this the NPC has waited long enough and gets the thoughtless answer. */
    private static final long DIALOGUE_PATIENCE_MILLIS = 20_000;

    private final Disposition disposition;
    private int steps;
    private int nextToShare;

    public Agent(InWorld connection, Mind mind, Policy policy, Disposition disposition) {
        this.connection = connection;
        this.mind = mind;
        this.policy = policy;
        this.disposition = disposition;
        this.executor = new IntentExecutor(connection.session());
        // Seeded from the name so a given agent paces the same way run to run, and two
        // agents never pace identically.
        this.voice = new Voice(new Random(mind.name().hashCode()));
        this.dialogueReader = policy.oracle().map(DialogueReader::new).orElse(null);
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
            // Leave the world rather than just stopping in it. A socket left open keeps the
            // character logged in as far as the server is concerned, and the next run is
            // refused with "already logged in" until the session times out - which makes
            // stopping and starting agents, the whole point of a daemon, not work.
            connection.session().close();
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

        answerWhenRead();
        voice.next(System.currentTimeMillis()).ifPresent(this::say);

        // Push the trace out to disk every step. Without this a buffered writer holds the
        // last few kilobytes indefinitely, so anything following the file live - a tail, or
        // the monitoring page - sees nothing until the run ends.
        mind.flush();

        long tick = perceiver.currentTick();
        Policy.Decision decision = policy.decide(mind, world, tick);

        // A policy that decided for itself does not bother naming itself; one that handed
        // the decision to its fallback does, and that difference is the whole point of
        // recording it.
        mind.decided(tick, decision.goal(), decision.intent().name(),
                decision.intent().detail(), decision.consultedBeliefs(), decision.considered(),
                decision.decidedBy() != null ? decision.decidedBy() : policy.name(),
                decision.fellBackBecause());

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
        // No model, or already thinking about the last thing it said: answer by reflex rather
        // than leave a conversation open with nobody attending it.
        if (dialogueReader == null || pendingDialogue != null) {
            connection.session().send(ClientPackets.npcTalkMore(
                    (byte) dialogue.style(), NPC_YES_OR_NEXT, NO_SELECTION));
            return;
        }
        pendingDialogue = new PendingDialogue((byte) dialogue.style(), System.currentTimeMillis(),
                CompletableFuture.supplyAsync(() -> dialogueReader.read(dialogue), reading));
    }

    /**
     * Sends the answer once there is one, or once the NPC has waited long enough.
     *
     * Checked on the tick rather than from the reading thread so that everything leaving this
     * agent leaves from one place, in the order the agent decided it.
     */
    private void answerWhenRead() {
        PendingDialogue pending = pendingDialogue;
        if (pending == null) {
            return;
        }
        boolean outOfPatience =
                System.currentTimeMillis() - pending.askedAtMillis() > DIALOGUE_PATIENCE_MILLIS;
        if (!pending.answer().isDone() && !outOfPatience) {
            return;
        }

        DialogueReader.Reply reply = DialogueReader.CONTINUE;
        if (pending.answer().isDone()) {
            reply = pending.answer().getNow(java.util.Optional.empty())
                    .orElse(DialogueReader.CONTINUE);
        } else {
            pending.answer().cancel(true);
        }

        log.debug("{} answers the NPC: {}", mind.name(), reply.why());
        connection.session().send(ClientPackets.npcTalkMore(
                pending.style(), reply.action(), reply.selection()));
        pendingDialogue = null;
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
                voice.reply("noted, though I have not seen that myself",
                        whisper.speakerName(), System.currentTimeMillis());
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
        answer.ifPresent(reply -> voice.reply(reply, replyTo, System.currentTimeMillis()));
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
        // Never take your own word for something. The speaker check in converse() catches the
        // map-chat echo, but only once the agent knows its own character id - and the first
        // position announcements go out in the seconds before the server has said who we are,
        // so they came back and were filed as hearsay about ourselves. An agent then held a
        // stale belief that it was somewhere it had since left, and treated it as a reason to
        // walk back and look for the company of itself. Checking the subject holds whenever
        // the claim arrives, which is the part the speaker check cannot promise.
        if (claim.subject().equals("player:" + world.characterId())) {
            return;
        }
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
        // Every other turn, say where you are instead of what you know - and say it even when
        // there is nothing else worth saying, which is the case for a freshly reset agent that
        // most needs to be found. Two agents that never mention their own whereabouts can only
        // ever meet by accident. It goes out as the speaker's player id rather than as "self",
        // because a listener can do nothing with somebody else's "self".
        boolean sayWhereIAm = nextToShare++ % 2 == 1
                && world.characterId() > 0 && world.mapId() > 0;
        if (!sayWhereIAm && worthSaying.isEmpty()) {
            return;
        }
        String claim = sayWhereIAm
                ? Claim.announce("player:" + world.characterId(), "in_map", "map:" + world.mapId())
                : Claim.announce(worthSaying.get((nextToShare / 2) % worthSaying.size()));

        long now = System.currentTimeMillis();
        voice.announce(claim, now);

        // Map chat only carries as far as the map, and agents that have diverged are by
        // definition somewhere else. Anyone it has met is reachable by name wherever they
        // are, which is how news actually travels between people who have split up. These
        // queue behind the announcement rather than going out with it, so telling four
        // people takes four beats, as it would if you were doing the telling.
        acquaintances().forEach(name -> voice.tell(claim, name, now));
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

    /** Whispers to one player, or says it to the whole map. */
    private void say(Voice.Utterance utterance) {
        connection.session().send(utterance.whisperTo() == null
                ? ClientPackets.chat(utterance.text(), false)
                : ClientPackets.whisper(utterance.whisperTo(), utterance.text()));
    }

    /**
     * Carries the clock on from a restored mind, before the loop starts.
     *
     * Called by whoever restored the mind rather than by the agent itself: an agent has no
     * business knowing it has been asleep.
     */
    public void resumeAt(long previousTick) {
        perceiver.resumeFrom(previousTick);
    }

    public long tick() {
        return perceiver.currentTick();
    }

    public Disposition disposition() {
        return disposition;
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
