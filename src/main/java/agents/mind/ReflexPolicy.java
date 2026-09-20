package agents.mind;

import agents.Mind;
import agents.memory.Belief;
import agents.world.QuestBoard;
import agents.world.WorldModel;

import java.awt.Point;
import java.util.ArrayList;
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

    /**
     * How many stretches of patience an agent will spend in one map before leaving whatever
     * else is going on. A hard ceiling rather than another judgement call: somewhere has to
     * say "you have seen this place" or a successful agent never sees anywhere else.
     */
    private static final int LIFETIMES_BEFORE_MOVING_ON = 2;
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
     * Decisions spent in a row on loot and monsters.
     *
     * The measure of how long an agent has had its head down. Reset by doing anything else,
     * which is the point: one look up is enough to reach an NPC, a quest or a door, and then
     * it is free to go back to what it was doing.
     */
    private int headDownFor;

    /**
     * Decisions left in which the agent will ignore loot and monsters.
     *
     * A single decision's worth of looking up achieved nothing, and the measurement said so:
     * the share of decisions spent fighting and looting went from 88% to 87%. Walking to an
     * NPC or a door takes many decisions, so one glance produced one orphaned step towards a
     * door the agent then spent another thirty decisions forgetting about. Looking up has to
     * last long enough to finish the errand.
     */
    private int lookingUpFor;

    /**
     * How much being neglected counts for.
     *
     * Has to be able to out-argue a good option at close range, or a neglected one never wins
     * and this is a ladder again with extra arithmetic. An agent standing on a monster scores
     * about 1.1 for fighting; a door nobody has taken for an attention span scores its own
     * appeal plus this.
     */
    private static final double NEGLECT_MATTERS = 1.5;

    /**
     * Added to the door already being walked to.
     *
     * Large, deliberately. Re-deciding every tick once took an agent one step towards a door
     * and then somewhere else, over and over: 199 decisions in a two-minute run, all of them
     * MoveTo, and it never left the starting town. Committing until arrival is the difference
     * between wandering and going somewhere.
     */
    private static final double COMMITTED = 2.0;

    /** There is always something to do, even if it is only walking about. */
    private static final double WANDERING_IS_BETTER_THAN_NOTHING = 0.05;

    /** Enough that something at your feet outranks a fight, whatever your disposition. */
    private static final double UNDERFOOT = 0.8;

    /** When each kind of thing was last done, for working out what is being neglected. */
    private final Map<String, Integer> lastChosenAt = new HashMap<>();

    /** Decisions for which something has been urged, by kind. See {@link #urge}. */
    private final Map<String, Integer> urgedFor = new HashMap<>();

    /**
     * How much an intention is worth against what is in front of the agent.
     *
     * Enough to change what it does, not enough to make it ignore a monster hitting it. An
     * intention is a lean, not an order.
     */
    private static final double URGED = 1.0;

    /**
     * The door just walked through, held until the next map arrives so the agent can find out
     * where it went.
     *
     * A portal tells you its name and nothing else - the client is not told where one leads,
     * which is why the prompt says "you do not know where it goes". Taking one and seeing
     * where you end up is the only way to find out, and it is worth writing down.
     */
    private String portalJustTaken;

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
            if (portalJustTaken != null && lastMapId >= 0) {
                // Learned the hard way, which is the only way available: that door goes here.
                mind.infer(portalJustTaken, "leads_to", "map:" + world.mapId(), tick);
                portalJustTaken = null;
            }
            lastMapId = world.mapId();
            decisionsHere = 0;
            decisionsSinceProgress = 0;
            committedPortal = null;     // the old map's doors are gone
        }
        decisionsHere++;
        decisionsMade++;

        if (world.level() > lastLevel) {
            lastLevel = world.level();
            decisionsSinceProgress = 0;
        } else {
            decisionsSinceProgress++;
        }

        Set<Integer> unfinished = startedQuests(mind);

        // Stopped getting anywhere. An agent holding a quest is exempt: it has no idea what the
        // quest asked for - that is deliberately unreadable - but whatever it was, staying is
        // likelier to advance it than leaving.
        boolean stale = unfinished.isEmpty() && decisionsSinceProgress > disposition.patience();

        List<String> consulted = mind.recall("map monster danger", tick, 3)
                .stream().map(Belief::ref).toList();
        Point self = world.selfPosition();

        List<Choice> choices = new ArrayList<>();
        lootNearby(choices, world, self);
        somethingToFight(choices, world, self);
        unfinishedBusiness(choices, world, self, unfinished);
        someoneToTalkTo(choices, world, self);
        awayOutOfHere(choices, world, mind, self, stale);
        choices.add(new Choice("wander", new Intent.MoveTo(
                new Point(self.x + random.nextInt(2 * WANDER_STEP) - WANDER_STEP, self.y)),
                "wander", WANDERING_IS_BETTER_THAN_NOTHING, null));

        ageUrges();

        Choice best = choices.stream().max(Comparator.comparingDouble(Choice::score)).orElseThrow();
        lastChosenAt.put(best.kind(), decisionsMade);
        if (best.onChosen() != null) {
            best.onChosen().run();
        }
        return new Decision(best.intent(), best.goal(), consulted, roadsNotTaken(choices, best));
    }

    /**
     * One thing the agent could do now, and how much it wants to.
     *
     * @param kind     what sort of thing this is, for working out what has been neglected
     * @param onChosen bookkeeping to run only if this is the one picked, so scoring an option
     *                 never has a side effect
     */
    private record Choice(String kind, Intent intent, String goal, double score, Runnable onChosen) {
    }

    /**
     * How long since the agent last did something of this kind, as a fraction of its attention
     * span, capped at one.
     *
     * This is what makes starvation impossible rather than merely unlikely. The ladder this
     * replaced tried loot, then monsters, then NPCs, then doors and stopped at the first match,
     * so the top two starved the rest whenever there was anything to hit - and they fed each
     * other, because killing makes drops and drops outrank monsters. Eighty-eight per cent of
     * one agent's decisions went on those two rungs. Here an option nobody has taken for a
     * while simply climbs until it wins, with no timers and nothing to tune.
     */
    private double neglect(String kind) {
        int since = decisionsMade - lastChosenAt.getOrDefault(kind, 0);
        return Math.min(1.0, (double) since / Math.max(1, disposition.attentionSpan()));
    }

    /**
     * Leans the agent towards a kind of thing for a while.
     *
     * This is how something slower and more thoughtful than a reflex gets a say. A model
     * asked once every thirty decisions cannot usefully choose a single action - by the time
     * it answers, the monster it was looking at is dead - but it can say what the agent should
     * be trying to do for the next half minute, and let reflexes work out how.
     *
     * <p>It also sidesteps the trap that caught two attempts at this: one decision's worth of
     * intent achieves nothing, because an NPC or a door is many decisions away. An urge
     * outlives the decision that set it.
     */
    public void urge(String kind, int forDecisions) {
        urgedFor.merge(kind, forDecisions, Math::max);
    }

    /**
     * What is currently being urged on this kind.
     *
     * Reads only. Ageing it here would age it once per option actually scored, so an urge
     * towards fighting would outlive one towards talking purely because there were monsters
     * about - and scoring an option would have a side effect, which is the mistake that had
     * agents committing to a door on the first decision of their lives.
     */
    private double urgeFor(String kind) {
        return urgedFor.getOrDefault(kind, 0) > 0 ? URGED : 0;
    }

    /** Whether something is currently being urged on this kind, for anything that asks. */
    public boolean isUrged(String kind) {
        return urgedFor.getOrDefault(kind, 0) > 0;
    }

    /** One decision's worth of forgetting, for every intention at once. */
    private void ageUrges() {
        urgedFor.replaceAll((kind, left) -> Math.max(0, left - 1));
    }

    /** 1 when standing on it, 0 at the edge of what the agent would cross for it. */
    private static double nearness(double distance, double range) {
        return distance >= range ? 0 : 1 - distance / range;
    }

    private void lootNearby(List<Choice> choices, WorldModel world, Point self) {
        world.nearestDrop().ifPresent(drop -> {
            double near = nearness(drop.position().distance(self), disposition.scavengeRange());
            if (near <= 0) {
                return;
            }
            // Underfoot beats everything, for anyone. Scoring greed against aggression alone
            // had a fighter step over the thing it had just knocked loose to go and hit
            // something else, which no player does: picking it up costs one decision.
            double underfoot = drop.position().distance(self) < MELEE_RANGE ? UNDERFOOT : 0;
            choices.add(new Choice("loot",
                    new Intent.PickUp(drop.objectId(), drop.position()),
                    "take what is at my feet",
                    (0.2 + disposition.greed()) * near + underfoot
                            + NEGLECT_MATTERS * neglect("loot") + urgeFor("loot"), null));
        });
    }

    private void somethingToFight(List<Choice> choices, WorldModel world, Point self) {
        world.nearestMonster().ifPresent(monster -> {
            double distance = monster.position().distance(self);
            double near = nearness(distance, disposition.pursuitRange());
            if (near <= 0) {
                return;
            }
            double score = (0.2 + disposition.aggression()) * near + NEGLECT_MATTERS * neglect("fight") + urgeFor("fight");
            Intent intent = distance < MELEE_RANGE
                    ? new Intent.Attack(monster.objectId(), monster.position())
                    : new Intent.MoveTo(monster.position());
            String goal = distance < MELEE_RANGE
                    ? "hit what is in front of me"
                    : "get closer to the thing I can see";
            choices.add(new Choice("fight", intent, goal, score, null));
        });
    }

    /**
     * Something owed to an NPC in sight.
     *
     * Scored high and rising with neglect, because an agent has no idea what a quest asked for
     * and the only way to find out whether it is done is to go back and offer.
     */
    private void unfinishedBusiness(List<Choice> choices, WorldModel world, Point self,
                                    Set<Integer> unfinished) {
        errandFor(world, unfinished).ifPresent(errand -> {
            WorldModel.Entity host = errand.npc();
            double distance = host.position().distance(self);
            double score = 0.8 + NEGLECT_MATTERS * neglect("errand") + urgeFor("errand");
            if (distance >= NPC_RANGE) {
                choices.add(new Choice("errand", new Intent.MoveTo(host.position()),
                        "go back to the one I owe something", score, null));
                return;
            }
            choices.add(new Choice("errand",
                    new Intent.CompleteQuest(errand.questId(), host.typeId(), host.position()),
                    "see if what I owe is done", score,
                    () -> lastHandIn.put(errand.questId(), decisionsMade)));
        });
    }

    private void someoneToTalkTo(List<Choice> choices, WorldModel world, Point self) {
        world.nearestNpc().ifPresent(npc -> {
            double distance = npc.position().distance(self);
            Optional<Integer> offer = QuestBoard.offeredBy(npc.typeId()).stream()
                    .filter(q -> !questsTried.contains(q))
                    .findFirst();
            // Something on offer is worth crossing a map for; a chat is worth a wander.
            double appeal = offer.isPresent() ? 0.7 : 0.2 + disposition.curiosity() * 0.3;
            double score = appeal + NEGLECT_MATTERS * neglect("talk") + urgeFor("talk");

            if (distance >= NPC_RANGE) {
                choices.add(new Choice("talk", new Intent.MoveTo(npc.position()),
                        "go and see what that one wants", score, null));
                return;
            }
            if (offer.isPresent()) {
                choices.add(new Choice("talk",
                        new Intent.StartQuest(offer.get(), npc.typeId(), npc.position()),
                        "take whatever this one is offering", score,
                        () -> questsTried.add(offer.get())));
                return;
            }
            choices.add(new Choice("talk",
                    new Intent.TalkTo(npc.objectId(), npc.typeId(), npc.position()),
                    "say hello and see what happens", score, null));
        });
    }

    private void awayOutOfHere(List<Choice> choices, WorldModel world, Mind mind, Point self,
                               boolean stale) {
        // Picking a candidate is not the same as setting off for one. Committing here, while
        // merely scoring the option, meant the commitment bonus applied on the very first
        // decision an agent ever made and the door beat everything for the rest of its life -
        // seven tests said so at once.
        boolean alreadyOnTheWay = committedPortal != null;
        WorldModel.PortalTarget door = alreadyOnTheWay ? committedPortal
                : chooseDoor(world.portals(), mind, world.mapId(), "player:" + world.characterId());
        if (door == null) {
            return;
        }

        // Wearing out a place makes the door more attractive; already being on the way makes it
        // much more so, because a door abandoned halfway is a door never reached. That was
        // learned the hard way: an agent once spent a two-minute run taking a single step
        // towards a door and then thinking better of it, over and over.
        double score = 0.15 + disposition.wanderlust() * 0.5
                + Math.min(1.0, (double) decisionsHere / disposition.patience())
                + (stale ? 1.0 : 0)
                + NEGLECT_MATTERS * neglect("door") + urgeFor("door")
                + (alreadyOnTheWay ? COMMITTED : 0);

        if (door.position().distance(self) < PORTAL_RANGE) {
            choices.add(new Choice("door",
                    new Intent.EnterPortal(door.name(), door.position()),
                    "see where this goes", score,
                    () -> {
                        portalJustTaken = portalRef(world.mapId(), door.name());
                        committedPortal = null;
                    }));
            return;
        }
        choices.add(new Choice("door", new Intent.MoveTo(door.position()),
                "walk to a way out", score, () -> committedPortal = door));
    }

    /**
     * What else was on the table, for the trace.
     *
     * The format has carried a {@code considered} field since the beginning and it was always
     * empty, because a ladder has nothing to say about the rungs it never reached. Scores do:
     * this is the line that would have made "eighty-eight per cent of decisions went on
     * fighting" obvious on sight rather than after a behavioural sample.
     */
    private static List<String> roadsNotTaken(List<Choice> choices, Choice taken) {
        return choices.stream()
                .filter(choice -> choice != taken)
                .sorted(Comparator.comparingDouble(Choice::score).reversed())
                .map(choice -> choice.kind() + "=" + Math.round(choice.score() * 100) / 100.0)
                .toList();
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

    /**
     * Picks a door, preferring the ones that lead somewhere new.
     *
     * Three tiers, in order: a door never taken from here, a door known to lead somewhere this
     * agent has never been, and finally anything at all. That ordering is the whole of the
     * urge to explore - no map of the world, no notion of where it ought to go, just a
     * preference for doors whose far side it cannot yet describe. An agent that has walked
     * every door in a map and found nothing new will still leave, because the last tier keeps
     * it moving.
     */
    private WorldModel.PortalTarget chooseDoor(List<WorldModel.PortalTarget> portals, Mind mind,
                                               int mapId, String selfRef) {
        if (portals.isEmpty()) {
            return null;
        }
        Set<String> beenThere = mapsVisited(mind);
        Set<String> companionsAre = companionMaps(mind, mapId, selfRef);

        List<WorldModel.PortalTarget> towardsCompany = new ArrayList<>();
        List<WorldModel.PortalTarget> untried = new ArrayList<>();
        List<WorldModel.PortalTarget> towardsSomewhereNew = new ArrayList<>();
        for (WorldModel.PortalTarget portal : portals) {
            Optional<String> leadsTo = destinationOf(mind, portalRef(mapId, portal.name()));
            if (leadsTo.isPresent() && companionsAre.contains(leadsTo.get())) {
                towardsCompany.add(portal);
            } else if (leadsTo.isEmpty()) {
                untried.add(portal);
            } else if (!beenThere.contains(leadsTo.get())) {
                towardsSomewhereNew.add(portal);
            }
        }

        // Company first, and only because someone said where they were and this agent had
        // already learned which door goes there. Both halves are things it found out.
        List<WorldModel.PortalTarget> preferred = !towardsCompany.isEmpty() ? towardsCompany
                : !untried.isEmpty() ? untried
                : !towardsSomewhereNew.isEmpty() ? towardsSomewhereNew
                : portals;
        return preferred.get(random.nextInt(preferred.size()));
    }

    /**
     * Every map this agent has ever stood in, from its own memory.
     *
     * Invalidated beliefs count: "I was in map 40000" stops being true when it leaves and
     * stays true as a thing that happened, which is exactly the distinction beliefs keep by
     * never being deleted.
     */
    private static Set<String> mapsVisited(Mind mind) {
        Set<String> maps = new HashSet<>();
        for (Belief belief : mind.semantic().all()) {
            if (belief.subject().equals("self") && belief.predicate().equals("in_map")) {
                maps.add(belief.object());
            }
        }
        return maps;
    }

    /**
     * Where other players were last said to be, excluding wherever this agent already is.
     *
     * Entirely hearsay: it comes from another agent announcing its own position in map chat
     * or a whisper. Nothing in the process is shared, so two agents on different machines
     * would find each other exactly the same way, or fail to in exactly the same way.
     */
    private static Set<String> companionMaps(Mind mind, int mapId, String selfRef) {
        String here = "map:" + mapId;
        Set<String> maps = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            // Skipping our own player id as well as our own map, because a mind saved before
            // agents stopped overhearing themselves still holds one of these, and it can never
            // be corrected now that such claims are refused - it would simply sit there
            // forever, sending the agent back to a map it left to look for itself.
            if (belief.subject().startsWith("player:") && !belief.subject().equals(selfRef)
                    && belief.predicate().equals("in_map")
                    && !belief.object().equals(here)) {
                maps.add(belief.object());
            }
        }
        return maps;
    }

    private static Optional<String> destinationOf(Mind mind, String portal) {
        return mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals(portal) && b.predicate().equals("leads_to"))
                .map(Belief::object)
                .findFirst();
    }

    private static String portalRef(int mapId, String name) {
        return "portal:" + mapId + "/" + name;
    }
}
