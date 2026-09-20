package agents.mind;

import agents.Mind;
import agents.memory.Belief;
import agents.world.QuestBoard;
import agents.world.WorldModel;

import java.awt.Point;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Fixed rules, no model, no reasoning.
 *
 * This exists to be the control. When an LLM policy runs later, the only way to say whether
 * it helped is to have something to compare it against on the same map, and that something
 * has to be simple enough that nobody suspects it of being clever. It also keeps agents
 * alive when the model is unreachable, and it costs nothing to run for hours.
 *
 * The rules are a priority ladder, roughly what a bored player does: pick up what is at your
 * feet, hit what is in front of you, bother an NPC now and then, take a door if there is
 * nothing else, otherwise wander. Note what it does <em>not</em> do - it has no notion that
 * monsters are worth killing, that one map is better than another, or what a quest is for.
 * It attacks what is near because it is near, and starts a quest because the marker is there.
 *
 * A {@link Disposition} scales the ladder's thresholds, so a population diverges instead of
 * walking in step.
 */
public class ReflexPolicy implements Policy {
    private static final int MELEE_RANGE = 60;
    private static final int PORTAL_RANGE = 40;
    private static final int WANDER_STEP = 80;
    private static final int NPC_RANGE = 60;

    private final Random random;
    private final Disposition disposition;

    /** Quests already started, so a refusal is not retried forever. */
    private final Set<Integer> questsTried = new HashSet<>();

    /**
     * When each quest was last offered back, so an agent keeps trying without pestering.
     * Unlike starting, handing in is worth retrying: the reason it failed a minute ago is
     * usually that the thing it needed had not happened yet.
     */
    private final Map<Integer, Integer> lastHandIn = new HashMap<>();
    private static final int HAND_IN_COOLDOWN = 60;

    private int decisionsSinceTalk;
    private int decisionsMade;
    private int decisionsHere;
    private int lastMapId = -1;

    /**
     * Decisions since anything went right, which is how an agent notices it has outstayed a
     * place.
     *
     * Levelling is the only progress signal an agent gets for free - it is told its own level
     * and nothing about what a map is worth - so "no level in a long time" stands in for "this
     * has stopped paying". Crude, and honest about being crude: it is a conclusion drawn from
     * the agent's own experience rather than a table of which map suits which level, which is
     * knowledge it is supposed to have to earn.
     */
    private int decisionsSinceProgress;
    private int lastLevel = -1;

    /**
     * The portal currently being walked to.
     *
     * Without this the policy re-decided every tick, took one step towards a door and then
     * wandered off again, so in a two-minute run it never reached one - 199 decisions, all
     * of them MoveTo, and the agent never left the starting town. Committing to a
     * destination until it is reached is the difference between wandering and going
     * somewhere.
     */
    private WorldModel.PortalTarget committedPortal;

    public ReflexPolicy(Random random) {
        this(random, Disposition.WANDERER);
    }

    public ReflexPolicy(Random random, Disposition disposition) {
        this.random = random;
        this.disposition = disposition;
    }

    public Disposition disposition() {
        return disposition;
    }

    @Override
    public Decision decide(Mind mind, WorldModel world, long tick) {
        if (world.mapId() != lastMapId) {
            lastMapId = world.mapId();
            decisionsHere = 0;
            decisionsSinceProgress = 0;
            committedPortal = null;     // the old map's doors are gone
        }
        decisionsHere++;

        if (world.level() > lastLevel) {
            lastLevel = world.level();
            decisionsSinceProgress = 0;
        } else {
            decisionsSinceProgress++;
        }
        boolean outstayed = decisionsSinceProgress > disposition.patience();

        List<String> consulted = mind.recall("map monster danger", tick, 3)
                .stream().map(Belief::ref).toList();
        Point self = world.selfPosition();

        Optional<WorldModel.Entity> drop = world.nearestDrop();
        if (drop.isPresent() && drop.get().position().distance(self) < disposition.scavengeRange()) {
            return new Decision(new Intent.PickUp(drop.get().objectId(), drop.get().position()),
                    "take what is at my feet", consulted, options());
        }

        // An agent that has stopped getting anywhere here stops taking the bait, so the ladder
        // falls through to the door rather than to the next monster.
        Optional<WorldModel.Entity> monster = outstayed ? Optional.empty() : world.nearestMonster();
        if (monster.isPresent() && monster.get().position().distance(self) < disposition.pursuitRange()) {
            WorldModel.Entity target = monster.get();
            if (target.position().distance(self) < MELEE_RANGE) {
                return new Decision(new Intent.Attack(target.objectId(), target.position()),
                        "hit what is in front of me", consulted, options());
            }
            return new Decision(new Intent.MoveTo(target.position()),
                    "get closer to the thing I can see", consulted, options());
        }

        // Unfinished business first. An agent has no idea what a quest asked for, so it goes
        // back and offers; the server says yes or nothing happens, and the state change is
        // how it finds out that whatever it did in between was the thing.
        decisionsMade++;
        Set<Integer> started = startedQuests(mind);
        Optional<Errand> errand = errandFor(world, started);
        if (errand.isPresent()) {
            WorldModel.Entity host = errand.get().npc();
            if (host.position().distance(self) >= NPC_RANGE) {
                return new Decision(new Intent.MoveTo(host.position()),
                        "go back to the one I owe something", consulted, options());
            }
            lastHandIn.put(errand.get().questId(), decisionsMade);
            return new Decision(
                    new Intent.CompleteQuest(errand.get().questId(), host.typeId(), host.position()),
                    "see if what I owe is done", consulted, options());
        }

        // Bother an NPC now and then. An agent has no idea what a quest is; it sees that this
        // one has something on offer and finds out by taking it.
        decisionsSinceTalk++;
        Optional<WorldModel.Entity> npc = world.nearestNpc();
        if (npc.isPresent() && decisionsSinceTalk >= disposition.talkInterval()) {
            WorldModel.Entity target = npc.get();
            if (target.position().distance(self) >= NPC_RANGE) {
                return new Decision(new Intent.MoveTo(target.position()),
                        "go and see what that one wants", consulted, options());
            }

            decisionsSinceTalk = 0;
            Optional<Integer> quest = QuestBoard.offeredBy(target.typeId()).stream()
                    .filter(q -> !questsTried.contains(q))
                    .findFirst();
            if (quest.isPresent()) {
                questsTried.add(quest.get());
                return new Decision(
                        new Intent.StartQuest(quest.get(), target.typeId(), target.position()),
                        "take whatever this one is offering", consulted, options());
            }
            return new Decision(new Intent.TalkTo(target.objectId(), target.typeId(), target.position()),
                    "say hello and see what happens", consulted, options());
        }

        // Nothing here. Head for a door, and keep heading for it until we arrive.
        if (committedPortal == null && (outstayed || decisionsHere % disposition.portalReluctance() == 0)) {
            List<WorldModel.PortalTarget> portals = world.portals();
            if (!portals.isEmpty()) {
                committedPortal = portals.get(random.nextInt(portals.size()));
            }
        }

        if (committedPortal != null) {
            if (committedPortal.position().distance(self) < PORTAL_RANGE) {
                Intent enter = new Intent.EnterPortal(committedPortal.name(), committedPortal.position());
                committedPortal = null;
                return new Decision(enter, "see where this goes", consulted, options());
            }
            return new Decision(new Intent.MoveTo(committedPortal.position()),
                    "walk to a way out", consulted, options());
        }

        Point wander = new Point(self.x + random.nextInt(2 * WANDER_STEP) - WANDER_STEP, self.y);
        return new Decision(new Intent.MoveTo(wander), "wander", consulted, options());
    }

    /** A quest the agent has started and an NPC in sight who can take it back. */
    private record Errand(int questId, WorldModel.Entity npc) {
    }

    private Optional<Errand> errandFor(WorldModel world, Set<Integer> started) {
        if (started.isEmpty()) {
            return Optional.empty();
        }
        return world.visibleNpcs().stream()
                .flatMap(npc -> QuestBoard.endedBy(npc.typeId()).stream()
                        .filter(started::contains)
                        .filter(this::offWorriedCooldown)
                        .map(questId -> new Errand(questId, npc)))
                .min(Comparator.comparingInt(Errand::questId));
    }

    private boolean offWorriedCooldown(int questId) {
        Integer last = lastHandIn.get(questId);
        return last == null || decisionsMade - last >= HAND_IN_COOLDOWN;
    }

    /**
     * Which quests it thinks it has going, read from its own beliefs rather than a separate
     * ledger - the agent acts on what it believes, and "quest:N state 1" is a belief it
     * formed by watching the quest start.
     */
    private static Set<Integer> startedQuests(Mind mind) {
        Set<Integer> started = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("state") && belief.object().equals("1")
                    && belief.subject().startsWith("quest:")) {
                try {
                    started.add(Integer.parseInt(belief.subject().substring(6)));
                } catch (NumberFormatException ignored) {
                    // not a quest ref after all
                }
            }
        }
        return started;
    }

    private static List<String> options() {
        return List.of("PickUp", "Attack", "TalkTo", "StartQuest", "CompleteQuest",
                "MoveTo", "EnterPortal", "Wait");
    }

    @Override
    public String name() {
        return "reflex:" + disposition.name();
    }
}
