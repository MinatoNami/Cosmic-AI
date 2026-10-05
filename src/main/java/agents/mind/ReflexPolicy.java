package agents.mind;

import agents.Mind;
import agents.memory.Belief;
import agents.world.KnownWorld;
import agents.world.Places;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Standing at a portal: level with it, and below it by no more than a portal floats.
     *
     * Portals are drawn above the floor - Thieves' Hideout's "tutorial" sits 46px up - and
     * measuring straight-line distance from the agent's feet meant one could never be reached.
     * Agent1 stood under it for hours, 46px away, waiting to be within 40.
     */
    static boolean atPortal(java.awt.Point self, java.awt.Point portal) {
        return Math.abs(portal.x - self.x) < PORTAL_RANGE
                && self.y - portal.y < PORTAL_FLOATS && portal.y - self.y < PORTAL_RANGE;
    }

    /** How far above the floor under it a portal is drawn, at most. */
    private static final int PORTAL_FLOATS = 80;
    private static final int WANDER_STEP = 80;

    /**
     * Where a wander is heading, kept until it gets there.
     *
     * A fresh random point either side of the agent on every decision made an idle agent
     * shuffle: 951 of one agent's thousand direction reversals were one wander followed by
     * another. A person wandering picks somewhere and walks to it.
     */
    private Point wanderingTo;
    private int wanderingSince;
    private int wanderDirection = 1;

    /** How far off along the floor a wander aims, and how long it is given to get there. */
    private static final int WANDER_MIN = 200;
    private static final int WANDER_MAX = 600;
    private static final int WANDER_PATIENCE = 30;
    private static final int NPC_RANGE = 60;

    private final Random random;
    private final Disposition disposition;

    /**
     * NPCs already spoken to that had nothing on offer.
     *
     * Saying hello twice is saying hello once too many. Without this, talking kept winning in
     * a town - neglect lifts any option by up to 1.5, so an agent that had greeted everybody
     * greeted them all again rather than walk east, and three maps from Southperry it stood in
     * Amherst having forty conversations in three minutes.
     */
    private final Map<Integer, Integer> greetedAt = new HashMap<>();

    /** When each shopkeeper was last walked up to, so one that did not open is not pestered. */
    private final Map<Integer, Integer> shopTriedAt = new HashMap<>();

    /** Where a shopkeeper stands, when the agent needs one and none is in sight; see Places. */
    private Set<String> shopMaps = Set.of();

    /**
     * How long an agent will take "nothing to say" for an answer.
     *
     * Not forever, which is what a plain set of greeted NPCs amounted to. What an NPC offers
     * changes: the one who will ferry you off Maple Island has nothing for you until you have
     * finished enough else, and an agent that crossed it off the first time it said hello
     * would never learn otherwise. Long enough to stop it pestering the same NPC every few
     * seconds, short enough that a morning's progress gets a fresh hearing.
     */
    private static final int WORTH_ANOTHER_ASK = 600;

    /**
     * Greetings sent and not yet answered, by NPC type, against the decision each went out on,
     * and how many times each NPC has said nothing at all.
     *
     * Some NPCs never answer - the server has no script for them and logs "not coded" - so no
     * conversation is ever recorded and they stay strangers for ever: a reason to travel to
     * their map, and a reason to say hello again on arrival. One agent greeted the three
     * people in one map eighty-three times between them without a word back. The silence is
     * something the agent can observe for itself, so it does.
     */
    private final Map<Integer, Integer> greetingSentAt = new HashMap<>();
    private final Map<Integer, Integer> silences = new HashMap<>();

    /** Decisions to wait for a dialogue window before calling a greeting unanswered. */
    private static final int ANSWER_WITHIN = 15;

    /**
     * Unanswered greetings before concluding somebody does not answer. More than one, in case
     * the first went unheard; not many more, because greetings to one NPC are six hundred
     * decisions apart and every one in between is a trip made for nothing.
     */
    private static final int SILENT_AFTER = 2;

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
     * Progress is finding something out - a level, a map never stood in, a door, a monster, a
     * person, a quest moving on; {@link Mind#novelties()} says exactly what counts. It used to
     * be a level or any change of map, which missed both ways an agent actually got stuck: a
     * fighter at level 23 levels too slowly to ever look stale while learning nothing, and an
     * agent bouncing between two maps reset the count on every bounce and so never did either.
     */
    private int decisionsSinceProgress;
    private long lastNovelties = -1;

    /** Whether the last decision was made feeling stuck. Read by whoever wants to step in. */
    private boolean stale;

    /**
     * How many stretches of patience a quest holds an agent in place for.
     *
     * An agent holding a quest has no idea what it asked for, and staying is likelier to
     * advance it than leaving - so some extra patience. It used to be unlimited, and a quest
     * that was never going to be finished held a fighter in one field for hours.
     */
    private static final int QUEST_PATIENCE = 3;

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
     * Has to outlast a distraction, not merely outweigh a calm one. Neglect can lift any
     * option by 1.5, so at 0.4 a half-finished walk to a door lost to whatever the agent had
     * not done lately, every time: it would set off, stop to talk, set off again, and never
     * arrive. A journey that cannot survive one distraction is not a journey.
     *
     * Three separate versions of this bug have now cost a night between them - 199 MoveTos
     * without leaving the starting town, a door abandoned every six decisions, and this.
     */
    private static final double COMMITTED = 1.2;

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
    private static final double URGED = 0.3;

    /**
     * How much an NPC's unfinished business is worth against the pull of whatever is in
     * front of the agent right now.
     *
     * Enough to get it walking to the door while there are still monsters about, not enough
     * to walk past a monster hitting it. Being owed a conversation is a strong reason to
     * travel and a weak reason to ignore your own health.
     */
    private static final double ERRAND = 0.6;

    /**
     * The door just walked through, held until the next map arrives so the agent can find out
     * where it went.
     *
     * A portal tells you its name and nothing else - the client is not told where one leads,
     * which is why the prompt says "you do not know where it goes". Taking one and seeing
     * where you end up is the only way to find out, and it is worth writing down.
     */
    /**
     * Doors walked into and not yet judged, against the decision each was tried on.
     *
     * A single slot lost every verdict but the last: the agent retried about every six
     * decisions and waited twelve before judging, so each attempt overwrote the pending one
     * and fifty-four tries produced a single conclusion. One entry per door instead.
     */
    private final Map<String, Integer> doorsAwaitingVerdict = new HashMap<>();

    /**
     * How long to wait for a door to do something before concluding it does not.
     *
     * Generous: a map change is several packets and the agent decides every 600ms.
     */
    private static final int DOOR_SHOULD_HAVE_WORKED = 12;

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

    /**
     * The door currently in mind, chosen once and kept.
     *
     * Separate from {@link #committedPortal}, because picking a candidate and setting off for
     * one are different things - and conflating them has now cost an evening twice over.
     * Committing while merely scoring gave the door a bonus on an agent's first ever decision.
     * Not remembering the candidate at all re-rolled it whenever some other option won a
     * decision, so an agent in Amherst, which has three unopened doors, spent four minutes
     * walking a few steps towards one, then a few towards another, and arrived at none.
     */
    private WorldModel.PortalTarget doorInMind;

    /** Which tier of chooseDoor produced the candidate, for the trace. */
    private String whyThisDoor = "";

    /**
     * Where the agent came into this map.
     *
     * The whole of its sense of direction. An agent has no map of the world and is not given
     * one, but it knows where it walked in, and the door furthest from that is the one
     * leading onward rather than back the way it came. Choosing at random among unopened
     * doors made exploring a coin flip per map: it reached Split Road of Destiny, one door
     * from Southperry, and wandered back west.
     */
    private Point arrivedAt;

    /**
     * What the agent is currently walking towards, of any kind.
     *
     * Only doors used to have commitment, so every other journey was re-argued from scratch
     * every 600ms. An agent in Amherst would set off east for a door, be out-scored halfway
     * by an NPC it owed a quest to in the west, turn round, be out-scored again, and cross
     * the same ground for four minutes without reaching either. Watching it from inside the
     * map is unmistakable: 1305, 1356, then 1281, 1206, 1131, 1056, 981, 906.
     *
     * Three door-shaped versions of this bug have been fixed tonight. This is the shape they
     * all had: whatever you have started has to be worth more than whatever you have not.
     */
    private String journeyKind;
    private Point journeyTo;

    /** The closest the agent has got to {@link #journeyTo}, and how long since it improved. */
    private double closestApproach = Double.MAX_VALUE;
    private int stalledFor;

    /**
     * Places the agent set off for, failed to reach, and is leaving alone for now.
     *
     * Keyed by a coarse grid rather than an exact point, because the thing that was
     * unreachable is the ledge, not the pixel - and a monster standing on it drifts a few
     * pixels every second. Cleared on leaving a map, where the coordinates mean nothing.
     */
    private final Map<String, Integer> gaveUpOn = new HashMap<>();

    /**
     * An NPC that told this agent what it needed first, and the map it was standing in.
     *
     * The agent was already writing these down - an NPC that says "come back when you are
     * level six and have a hundred and fifty mesos" produces a {@code wants_first} belief -
     * and then nothing whatsoever read them. So it heard the one instruction that leads off
     * the island, recorded it faithfully, walked away and never went back.
     *
     * Holding the errand is the other half: once the agent can pay what was asked, the map
     * that NPC was in stops being just another map and becomes somewhere it is going.
     */
    private String errandMap;
    private int errandNpc = -1;

    /**
     * The map the agent is on its way to, which it keeps heading for until it gets there.
     *
     * Chosen once from {@link Places} and held across maps, rather than re-derived in every
     * room from whichever rule happened to have something to say there - which is how an
     * agent went Southperry, Split Road, Southperry four hundred times in five minutes, each
     * leg a sensible answer to a different question.
     */
    private String destination;
    private String destinationWhy;
    private boolean destinationFromModel;
    private int destinationSince;

    /** Maps the agent reached as a destination, against the decision it arrived. */
    private final Map<String, Integer> reachedAsDestination = new HashMap<>();

    /**
     * How long a destination is worth pursuing before concluding the way there does not work.
     * Long, because a destination four maps off is a lot of walking; the map changes along the
     * way are what show it is going somewhere.
     */
    private static final int DESTINATION_PATIENCE = 900;

    /**
     * How long a place stays less attractive after the agent went there for something. Long
     * enough that the trip back is not the next thing it does, short enough that a town it
     * will need again is not written off.
     */
    private static final int JUST_BEEN_FOR = 1500;

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

    /** Nowhere new on foot and nothing to hunt, for long enough to call the agent stranded. */
    private boolean strandedHere;

    /**
     * Whether nothing unexplored can be reached on foot from here: no door anywhere it can walk
     * to that it has not opened. Asked now and then rather than every decision, since it walks
     * the whole known map. Happyville has doors - into its own houses - so "no door worth
     * trying" was never true there; "nowhere new to walk to" is.
     */
    private boolean nothingNewOnFoot(Mind mind, WorldModel world) {
        if (decisionsMade - checkedOnFootAt >= RECHECK_ON_FOOT || checkedOnFootIn != world.mapId()) {
            checkedOnFootAt = decisionsMade;
            checkedOnFootIn = world.mapId();
            KnownWorld known = KnownWorld.rememberedBy(mind.semantic().liveBeliefs());
            String here = KnownWorld.mapRef(world.mapId());
            // Nothing unopened, and nothing to hunt anywhere it can walk to. A town with fields
            // next door is not stranding anybody, however well it is known.
            onFootIsSpent = known.routeToNearestFrontier(here).isEmpty()
                    && known.reachableFrom(here, Set.of()).keySet().stream().noneMatch(known::huntingIn);
        }
        return onFootIsSpent;
    }

    private int checkedOnFootAt = Integer.MIN_VALUE / 2;
    private int checkedOnFootIn = -1;
    private boolean onFootIsSpent;
    private static final int RECHECK_ON_FOOT = 50;

    /** Decisions with nowhere new on foot and nothing to hunt before the agent is stranded. */
    private static final int STRANDED_AFTER = 100;

    /** How soon a stranded agent asks the same people again. */
    private static final int STRANDED_ASK = 60;

    /**
     * How long before somebody already spoken to is worth asking again.
     *
     * Normally long, so as not to pester. But somewhere with no working door and nothing to
     * hunt, the people are the only way on - Happyville is left by asking one NPC - and an
     * agent that had heard them all once wandered there for hours, waiting out a cooldown
     * meant for places with something else to do.
     */
    private int askAgainAfter() {
        return strandedHere ? STRANDED_ASK : WORTH_ANOTHER_ASK;
    }

    /** The tick the agent last walked into a door, and the tick being decided now. */
    private long lastDoorTriedAt = -1;
    private long currentTick;

    /** Episodes looked back through for a conversation since the last door. */
    private static final int RECENT_ENOUGH = 60;

    /**
     * Whether somebody spoke to the agent after it last tried a door.
     *
     * If so, a map change is theirs: NPCs move people about, and the door the agent happened
     * to be standing by did not. Blaming the door is how Happyville's st00 - a dead portal two
     * steps from the NPC who takes you home - came to "lead to Sleepywood".
     */
    static boolean movedByConversation(List<agents.memory.Episode> recent, long doorTriedAt) {
        return recent.stream().anyMatch(episode -> episode.tick() >= doorTriedAt
                && episode.observation() instanceof agents.percept.Observation.DialogueShown);
    }

    @Override
    public Decision decide(Mind mind, WorldModel world, long tick) {
        currentTick = tick;
        boolean wasStranded = strandedHere;
        strandedHere = world.monsterCount() == 0 && decisionsHere > STRANDED_AFTER && nothingNewOnFoot(mind, world);
        if (strandedHere && !wasStranded) {
            // Worth remembering, and passing on: whoever offers to bring an agent here is
            // offering it nothing.
            mind.infer(KnownWorld.mapRef(world.mapId()), "nothing_to_do", "true", tick);
        }
        // A door that did nothing is worth knowing about. An agent found two in Amherst -
        // tuto00 and in00, a tutorial portal and a shop entrance - walked into them
        // thirty-six times in three minutes and never moved an inch. Each failure left it in
        // the same map, so the map went on wearing out, so the door scored higher, so it tried
        // again. Noticing costs one comparison and breaks the loop.
        for (var awaiting = doorsAwaitingVerdict.entrySet().iterator(); awaiting.hasNext(); ) {
            var door = awaiting.next();
            if (decisionsMade - door.getValue() > DOOR_SHOULD_HAVE_WORKED) {
                mind.infer(door.getKey(), "leads_to", NOWHERE, tick);
                awaiting.remove();
            }
        }

        if (world.mapId() != lastMapId) {
            if (!doorsAwaitingVerdict.isEmpty() && lastMapId >= 0
                    && !movedByConversation(mind.episodic().recent(RECENT_ENOUGH), lastDoorTriedAt)) {
                // Whichever door was tried most recently is the one that worked; the rest were
                // tried from a map we are no longer in and can never be judged now.
                String worked = doorsAwaitingVerdict.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                mind.infer(worked, "leads_to", "map:" + world.mapId(), tick);
            }
            doorsAwaitingVerdict.clear();
            cameFrom = lastMapId >= 0 ? KnownWorld.mapRef(lastMapId) : null;
            lastMapId = world.mapId();
            recentMaps.addLast(world.mapId());
            while (recentMaps.size() > 6) {
                recentMaps.removeFirst();
            }
            if (bouncing()) {
                // Whatever was drawing it back and forth is not getting it anywhere.
                destination = null;
                destinationFromModel = false;
            }
            decisionsHere = 0;
            wanderingTo = null;         // somewhere in the last map means nothing here
            String arrivedIn = KnownWorld.mapRef(world.mapId());
            if (arrivedIn.equals(destination)) {
                reachedAsDestination.put(arrivedIn, decisionsMade);
                destination = null;
            }
            committedPortal = null;     // the old map's doors are gone
            doorInMind = null;
            journeyKind = null;         // and nothing here is where we were going
            gaveUpOn.clear();           // and nowhere here is somewhere we failed to reach
            arrivedAt = world.selfPosition();
            // Write down which doors this place has. Noticing the exits of the room you are
            // standing in is looking, not deduction - and it is the one thing that lets an
            // agent realise, from two maps away, that it left one of them unopened. Without
            // it the frontier is invisible the moment you walk out.
            for (WorldModel.PortalTarget door : world.portals()) {
                mind.saw(KnownWorld.mapRef(world.mapId()), "has_door", door.name(), tick);
            }
        }
        decisionsHere++;
        decisionsMade++;
        watchTheJourney(world.selfPosition());

        if (mind.novelties() != lastNovelties) {
            lastNovelties = mind.novelties();
            decisionsSinceProgress = 0;
        } else {
            decisionsSinceProgress++;
        }

        takeStockOfWhatIsOwed(mind, world, tick);
        listenForAnswers(mind, tick);

        Set<Integer> unfinished = startedQuests(mind);
        waitingOnUs = waitingOn(mind);

        // Stopped finding anything out. A quest buys more patience, not an exemption.
        stale = decisionsSinceProgress
                > disposition.patience() * (unfinished.isEmpty() ? 1 : QUEST_PATIENCE);

        List<String> consulted = mind.recall("map monster danger", tick, 3)
                .stream().map(Belief::ref).toList();
        Point self = world.selfPosition();

        List<Choice> choices = new ArrayList<>();
        lootNearby(choices, world, self, tick);
        somethingToFight(choices, world, self);
        unfinishedBusiness(choices, world, self, unfinished);
        someoneToTalkTo(choices, world, mind, self);
        somewhereToSell(choices, world, mind, self, tick);
        Map<String, Integer> deaths = deathsNearLevel(mind, world.level());
        deathsHere = deaths.getOrDefault(KnownWorld.mapRef(world.mapId()), 0);
        Set<String> grounds = new HashSet<>();
        deaths.forEach((map, died) -> {
            if (died >= Places.KILLING_GROUND && !map.equals(KnownWorld.mapRef(world.mapId()))) {
                grounds.add(map);
            }
        });
        killingGrounds = grounds;
        rethinkAfterDying(deaths);
        awayOutOfHere(choices, world, mind, self, stale);
        choices.add(new Choice("wander", new Intent.MoveTo(wanderTarget(world, self)),
                "wander", WANDERING_IS_BETTER_THAN_NOTHING, null));
        backOffWhenHurt(choices, world, self);

        ageUrges();

        // Somewhere the agent has just failed to reach is not a candidate, however well it
        // scores. Without this the give-up is undone on the very next decision: the ledge is
        // still the nearest NPC, it still wins, and the agent sets off for it again.
        List<Choice> reachable = choices.stream()
                .filter(choice -> !(choice.intent() instanceof Intent.MoveTo going)
                        || !outOfMind(going.destination()))
                .toList();
        Choice best = (reachable.isEmpty() ? choices : reachable).stream()
                .max(Comparator.comparingDouble(Choice::score)).orElseThrow();
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
    /** True when nothing new has been found for longer than this agent's patience. */
    public boolean isStale() {
        return stale;
    }

    /** Decisions since this agent last found something out. */
    public int decisionsSinceProgress() {
        return decisionsSinceProgress;
    }

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

    /**
     * What an unfinished journey towards this thing is worth.
     *
     * Keyed on where it was going as well as what kind, so a new monster does not inherit the
     * commitment earned walking towards a different one.
     */
    private double commitmentTo(String kind, Point target) {
        if (!kind.equals(journeyKind) || journeyTo == null) {
            return 0;
        }
        return journeyTo.distance(target) < SAME_ERRAND ? COMMITTED : 0;
    }

    /** Close enough to count as the same destination between one decision and the next. */
    private static final int SAME_ERRAND = 120;

    /**
     * How much closer a journey has to get before it counts as going anywhere.
     *
     * A walking step is about {@code STEP_PIXELS} wide, so a journey that is working closes
     * far more than this per decision. The bar is low on purpose: this is meant to catch a
     * journey making no progress at all, not to hurry a slow one.
     */
    private static final int GETTING_SOMEWHERE = 30;

    /**
     * How many decisions a journey may make no progress before the agent gives up on it.
     *
     * Doors have had this since the night an agent walked into the same dead tutorial portal
     * fifty-two times; nothing else did. Two agents wiped clean and set loose landed in Lith
     * Harbor, recorded all twenty-eight of its exits, tried none of them, and spent fifteen
     * minutes walking towards one NPC on a ledge they cannot climb to - hundreds of MoveTo
     * to the same point, the commitment bonus re-winning the argument every time. Deciding
     * to go somewhere has to be revocable, or the first unreachable thing an agent fancies
     * is the last decision it ever makes.
     */
    private static final int JOURNEY_SHOULD_BE_GETTING_SOMEWHERE = 15;

    /**
     * How long somewhere stays out of mind after the agent fails to reach it.
     *
     * Not forever. Unreachable usually means "not from this ledge, in this direction, with
     * what I know about walking" - and all three change. Long enough that the agent gets on
     * with something else first.
     */
    private static final int WORTH_TRYING_AGAIN = 300;

    /** Remembers a journey begun, so the next decision knows it is already under way. */
    private void settingOff(String kind, Point target) {
        // A new journey, not the same one re-confirmed. A monster drifts a few pixels every
        // decision and re-registers its journey each time; resetting the progress watch on
        // that would mean it never notices anything is wrong.
        boolean somewhereElse = !kind.equals(journeyKind) || journeyTo == null
                || journeyTo.distance(target) >= SAME_ERRAND;
        if (somewhereElse) {
            closestApproach = Double.MAX_VALUE;
            stalledFor = 0;
        }
        journeyKind = kind;
        journeyTo = target;
    }

    /**
     * Notices a journey that is not getting anywhere and calls it off.
     *
     * Distance to the target, against the closest the agent has ever managed. Anything else
     * - time, step count, whether the server acknowledged the move - can look like progress
     * while the agent stands still, and standing still is precisely the failure. Where it
     * was going goes out of mind afterwards, or the next decision picks it straight back up
     * and nothing has changed.
     */
    private void watchTheJourney(Point self) {
        if (journeyKind == null || journeyTo == null) {
            return;
        }
        double distance = journeyTo.distance(self);
        if (distance < closestApproach - GETTING_SOMEWHERE) {
            closestApproach = distance;
            stalledFor = 0;
            return;
        }
        if (++stalledFor < JOURNEY_SHOULD_BE_GETTING_SOMEWHERE) {
            return;
        }
        gaveUpOn.put(whereabouts(journeyTo), decisionsMade);
        if ("door".equals(journeyKind)) {
            doorInMind = null;          // pick a different way out next time
            committedPortal = null;
        }
        arrived();                      // stop pretending we are still on our way
    }

    /** Whether somewhere is one the agent recently failed to reach. */
    private boolean outOfMind(Point target) {
        Integer gaveUp = gaveUpOn.get(whereabouts(target));
        return gaveUp != null && decisionsMade - gaveUp < WORTH_TRYING_AGAIN;
    }

    /** A place, coarsely - near enough to the same spot is the same spot. */
    private static String whereabouts(Point where) {
        return Math.floorDiv(where.x, SAME_ERRAND) + ":" + Math.floorDiv(where.y, SAME_ERRAND);
    }

    /** Arrived, or done with it either way. */
    private void arrived() {
        journeyKind = null;
        journeyTo = null;
    }

    /**
     * Anything said to an NPC voids whatever verdict a door was awaiting.
     *
     * An NPC can move you. One of them - standing a few steps from where every new character
     * appears - asks "would you like to skip the tutorials and head straight to Lith
     * Harbor?" and warps you clean off the island. An agent that had walked into a door
     * shortly before, and was still waiting to see whether it did anything, credited the
     * door with the journey: {@code portal:10000/glBmsg1 leads_to Lith Harbor}, about a
     * portal the map data gives no destination and no script, and which therefore cannot
     * move anybody at all.
     *
     * A false edge is worse than a missing one. A missing edge costs a walk to rediscover; a
     * false one is a road on the map that was never there, and everything that plans a route
     * plans along it. Declining to guess is the cheaper mistake - and it matters more now
     * that minds are inherited, because a wrong edge would be handed to every generation
     * after this one.
     */
    private void spokeToSomeone() {
        doorsAwaitingVerdict.clear();
    }

    /** 1 when standing on it, 0 at the edge of what the agent would cross for it. */
    private static double nearness(double distance, double range) {
        return distance >= range ? 0 : 1 - distance / range;
    }

    private void lootNearby(List<Choice> choices, WorldModel world, Point self, long tick) {
        // A full bag rules out everything but money until it has had time to change.
        world.nearestDropWorthTaking(tick).ifPresent(drop -> {
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
            double score = (0.2 + disposition.aggression()) * near
                    + NEGLECT_MATTERS * neglect("fight") + urgeFor("fight")
                    + commitmentTo("fight", monster.position());
            boolean withinReach = distance < MELEE_RANGE;
            Intent intent = withinReach
                    ? new Intent.Attack(monster.objectId(), monster.position())
                    : new Intent.MoveTo(monster.position());
            String goal = withinReach
                    ? "hit what is in front of me"
                    : "get closer to the thing I can see";
            Point where = monster.position();
            choices.add(new Choice("fight", intent, goal, score,
                    withinReach ? this::arrived : () -> settingOff("fight", where)));
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
            double score = 0.8 + NEGLECT_MATTERS * neglect("errand") + urgeFor("errand")
                    + commitmentTo("errand", host.position());
            if (!IntentExecutor.canSpeakTo(world.mapId(), self, host.position(), NPC_RANGE)) {
                Point where = host.position();
                choices.add(new Choice("errand", new Intent.MoveTo(host.position()),
                        "go back to the one I owe something", score,
                        () -> settingOff("errand", where)));
                return;
            }
            choices.add(new Choice("errand",
                    new Intent.CompleteQuest(errand.questId(), host.typeId(), host.position()),
                    "see if what I owe is done", score,
                    () -> {
                        lastHandIn.put(errand.questId(), decisionsMade);
                        handInsAtLevel.merge(errand.questId(), new int[] {1, world.level()},
                                (was, now) -> was[1] == now[1] ? new int[] {was[0] + 1, was[1]} : now);
                        spokeToSomeone();
                        arrived();
                    }));
        });
    }

    /**
     * A shopkeeper, when the agent needs one.
     *
     * Needing one is a full bag - the loot it walks past is the cost of not going - or
     * knowing what heals and carrying none of it, with money to buy some. A shopkeeper is
     * anyone whose conversation has opened a shop before; one in sight is walked to and
     * talked to, and the counter does the rest. One elsewhere becomes a reason to travel,
     * weighed with every other in {@link Places}.
     *
     * Any shop buys, so a full bag takes the agent to whichever is nearest. Only some sell
     * what heals, and going for that took an agent to the pet-food seller on Pet-Walking
     * Road forty times an hour for potions it had never stocked; so running out of what
     * heals goes only to somebody seen with it on their shelves.
     */
    private void somewhereToSell(List<Choice> choices, WorldModel world, Mind mind, Point self,
                                 long tick) {
        shopMaps = Set.of();
        if (!needsAShop(world, mind, tick)) {
            return;
        }
        boolean toSell = world.inventoryFull(tick);
        Set<String> keepers = toSell ? shopkeepers(mind) : stocking(mind, knownHealers(mind));
        keepers.removeAll(noUseNow(mind, world));
        Optional<WorldModel.Entity> keeper = world.visibleNpcs().stream()
                .filter(npc -> keepers.contains("npc:" + npc.typeId()))
                .filter(npc -> decisionsMade - shopTriedAt.getOrDefault(npc.typeId(), -SHOP_AGAIN) >= SHOP_AGAIN)
                .min(Comparator.comparingDouble(npc -> npc.position().distance(self)));
        if (keeper.isEmpty()) {
            Set<String> maps = new HashSet<>();
            String here = KnownWorld.mapRef(world.mapId());
            for (String who : keepers) {
                lastSeenIn(mind, who).filter(map -> !map.equals(here)).ifPresent(maps::add);
            }
            shopMaps = Set.copyOf(maps);
            return;
        }
        WorldModel.Entity npc = keeper.get();
        double score = SHOP_URGENCY + NEGLECT_MATTERS * neglect("shop")
                + commitmentTo("shop", npc.position());
        String why = toSell ? "sell what I cannot carry" : "buy something that heals";
        if (!IntentExecutor.canSpeakTo(world.mapId(), self, npc.position(), NPC_RANGE)) {
            Point where = npc.position();
            choices.add(new Choice("shop", new Intent.MoveTo(where),
                    "go and " + why, score, () -> settingOff("shop", where)));
            return;
        }
        choices.add(new Choice("shop", new Intent.TalkTo(npc.objectId(), npc.typeId(), npc.position()),
                why, score, () -> {
                    shopTriedAt.put(npc.typeId(), decisionsMade);
                    arrived();
                }));
    }

    /** Enough to outrank a fight: a full bag makes every kill worth only its money. */
    private static final double SHOP_URGENCY = 2.0;

    /** Decisions before walking up to a shopkeeper who did not open a shop again. */
    private static final int SHOP_AGAIN = 30;

    /** Whether the agent has business with a shop: no room, or nothing left that heals. */
    static boolean needsAShop(WorldModel world, Mind mind, long tick) {
        if (world.inventoryFull(tick)) {
            return true;
        }
        if (world.inventory().meso() < MONEY_FOR_POTIONS) {
            return false;
        }
        Set<String> healers = knownHealers(mind);
        return !healers.isEmpty()
                && healers.stream().allMatch(item -> world.inventory().count(itemIdIn(item)) == 0)
                && !stocking(mind, healers).isEmpty();
    }

    /** Items the agent has seen restore its health, as refs. */
    private static Set<String> knownHealers(Mind mind) {
        Set<String> healers = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("restores_hp") && belief.object().equals("true")
                    && itemIdIn(belief.subject()) > 0) {
                healers.add(belief.subject());
            }
        }
        return healers;
    }

    /** Shopkeepers seen with any of these on their shelves. */
    private static Set<String> stocking(Mind mind, Set<String> items) {
        Set<String> keepers = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("sells") && items.contains(belief.object())) {
                keepers.add(belief.subject());
            }
        }
        return keepers;
    }

    private static int itemIdIn(String itemRef) {
        if (!itemRef.startsWith("item:")) {
            return -1;
        }
        try {
            return Integer.parseInt(itemRef.substring("item:".length()));
        } catch (NumberFormatException notAnId) {
            return -1;
        }
    }

    /** Enough for a handful of the cheapest potions. */
    private static final int MONEY_FOR_POTIONS = 100;

    /**
     * Shops that sold it nothing and bought nothing from it last time, while nothing has
     * changed since: same money, nothing new in its bags. Going back would go the same way.
     */
    private Set<String> noUseNow(Mind mind, WorldModel world) {
        Set<String> useless = new HashSet<>();
        String meso = String.valueOf(world.inventory().meso());
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("shop_was_no_use") && belief.object().equals(meso)
                    && belief.lastSeen() >= newThingSince) {
                useless.add(belief.subject());
            }
        }
        return useless;
    }

    private static Set<String> shopkeepers(Mind mind) {
        Set<String> keepers = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("runs_shop") && belief.object().equals("true")) {
                keepers.add(belief.subject());
            }
        }
        return keepers;
    }

    /**
     * Which visible NPC is worth a decision.
     *
     * The nearest one used to win by default, and that is how an agent stood in Southperry
     * for half an hour among people it had already met while Shanks - who sells the only
     * passage off the island - waited two platforms above it, seen fourteen times and never
     * approached. One agent's high-water mark was y=140, which is exactly the height of the
     * NPC it had already spoken to.
     *
     * So somebody it has never spoken to outranks somebody it has, and among equals the
     * nearest still wins. First-hand conversations only: an inherited one was a
     * predecessor's, and finding out for yourself is the point.
     */
    private Optional<WorldModel.Entity> worthTalkingTo(WorldModel world, Mind mind,
                                                       Point self) {
        Set<String> met = spokenTo(mind);
        // Somebody with a quest not yet asked about is as worth seeing as a stranger. Heena,
        // once heard, fell behind Sera, a stranger across the map; after Sera the doors
        // outscored going back, and Heena's quest - the one Sera was waiting to finish - was
        // never asked about by anybody.
        Comparator<WorldModel.Entity> strangersFirst = Comparator.comparingInt(
                npc -> met.contains("npc:" + npc.typeId()) && !hasSomethingToOffer(npc) ? 1 : 0);
        // Stranded, everybody has been heard and somebody here is the way out; ask whoever was
        // asked longest ago, so the asking goes round the room rather than back to the nearest.
        if (strandedHere) {
            strangersFirst = strangersFirst.thenComparingInt(npc -> greetedAt.getOrDefault(npc.typeId(), -1));
        }
        Set<String> stranders = strandedBy(mind);
        silentOnes = doesNotAnswer(mind);
        return world.visibleNpcs().stream()
                .filter(npc -> !stranders.contains("npc:" + npc.typeId()))
                // Nor somebody it has just failed to reach. Chosen anyway, the choice was
                // thrown out afterwards with nobody in its place: a stranger on a ledge above
                // Happyville won every time, was dropped every time, and the NPC who takes
                // people home stood a few steps away, never once considered.
                .filter(npc -> !outOfMind(npc.position()))
                .filter(this::stillWorthAsking)
                .min(strangersFirst.thenComparingDouble(
                        npc -> npc.position().distance(self)));
    }

    /**
     * Whether this one is worth choosing at all just now.
     *
     * The check used to happen after the choice was made, which meant one rejected candidate
     * cost the agent the whole decision - nobody else was considered. It mattered because
     * some NPCs never answer: the server logs "NPC 21000 is not coded", no dialogue window
     * opens, so no conversation is ever recorded and they stay strangers forever. An agent
     * in Southperry picked the nearest such stranger, got nothing, was barred from asking it
     * again for six hundred decisions, and produced no talk option at all in the meantime -
     * while Shanks, three platforms up and the only reason to be in that map, went
     * unconsidered because the selection had already been spent.
     */
    /**
     * Somebody in sight with a quest not yet asked about, or who finishes a quest under way
     * that has not yet been offered to them. Each is tried once, so this runs out; and in case
     * something keeps it from running out, it holds the agent for so long and no longer.
     */
    private boolean somethingToDoHere(WorldModel world, Mind mind) {
        if (decisionsHere > BUSINESS_HOLDS) {
            return false;
        }
        Set<String> stranders = strandedBy(mind);
        Set<Integer> started = startedQuests(mind);
        for (WorldModel.Entity npc : world.visibleNpcs()) {
            if (stranders.contains("npc:" + npc.typeId())) {
                continue;
            }
            if (hasSomethingToOffer(npc)) {
                return true;
            }
            for (int quest : QuestBoard.endedBy(npc.typeId())) {
                if (started.contains(quest) && !lastHandIn.containsKey(quest)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Decisions in one map that quests in it can keep an agent from leaving. */
    private static final int BUSINESS_HOLDS = 300;

    private boolean hasSomethingToOffer(WorldModel.Entity npc) {
        return QuestBoard.offeredBy(npc.typeId()).stream()
                .anyMatch(quest -> !questsTried.contains(quest));
    }

    private boolean stillWorthAsking(WorldModel.Entity npc) {
        if (npc.typeId() == errandNpc) {
            return true;
        }
        if (hasSomethingToOffer(npc)) {
            return true;        // taking a quest does not depend on them having anything to say
        }
        if (silentOnes.contains("npc:" + npc.typeId())) {
            return false;
        }
        Integer greeted = greetedAt.get(npc.typeId());
        return greeted == null || decisionsMade - greeted >= askAgainAfter();
    }

    /**
     * Who the agent has spoken to, got nothing from, and has left alone long enough.
     *
     * Somebody who named a price is finished with - the agent already holds what they had to
     * give, and wants_first will bring it back when it can pay. Somebody who said nothing
     * useful is a different case entirely and had no way back at all: met, therefore not a
     * stranger, therefore nowhere worth travelling to. That is how an agent came away from
     * Shanks empty-handed during the window when dialogues were timing out and never
     * returned to the one person holding the way off the island.
     *
     * The cooldown is the same one that stops it pestering anybody else, so this cannot
     * become a loop: it will travel back, and then wait its turn to ask.
     */
    private Set<String> worthHearingAgain(KnownWorld known) {
        Set<String> again = new HashSet<>();
        Set<String> alreadyTold = known.whoNamedAPrice();
        for (String who : known.everyoneSpokenTo()) {
            if (alreadyTold.contains(who)) {
                continue;
            }
            Integer asked = greetedAt.get(npcIdIn(who));
            if (asked == null || decisionsMade - asked >= WORTH_ANOTHER_ASK) {
                again.add(who);
            }
        }
        return again;
    }

    /**
     * Everyone who has taken this agent somewhere it could not leave.
     *
     * Declining their offer is not enough on its own: an NPC it has spoken to and "learned
     * nothing from" is somebody worth going back to, so it walked up, heard the same offer,
     * said no, and did it again.
     */
    private static Set<String> strandedBy(Mind mind) {
        Set<String> who = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("strands_you")) {
                who.add(belief.subject());
            }
        }
        return who;
    }

    /** Who does not answer, as of the last time anybody was chosen to talk to. */
    private Set<String> silentOnes = Set.of();

    /** Everyone this agent has found does not answer when spoken to. */
    static Set<String> doesNotAnswer(Mind mind) {
        long now = mind.episodic().byId(mind.episodic().latestId())
                .map(agents.memory.Episode::tick).orElse(0L);
        Set<String> who = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("does_not_answer") && now - belief.lastSeen() < SILENCE_LASTS) {
                who.add(belief.subject());
            }
        }
        return who;
    }

    /**
     * How long finding somebody silent counts for, since it was last found so.
     *
     * For ever, it was wrong for ever: a shop menu left open with no choice made held every
     * conversation shut, so for 550 greetings in a row nobody answered anybody, and 27 NPCs -
     * John, Mr. Oh and others who talk perfectly well - were written down as silent and passed
     * to the next generation. Now the agent tries again after a while. Somebody truly silent
     * is found so again, which renews it; somebody who answers simply stops counting.
     */
    static final long SILENCE_LASTS = 6_000;

    /**
     * Settles each greeting that has had long enough to be answered.
     *
     * Answered means a dialogue window came back, which is what {@code talks_in} records. An
     * NPC that has ever answered is never called silent: once it has spoken, a missed hello
     * is more likely a busy moment than a person with nothing to say.
     */
    private void listenForAnswers(Mind mind, long tick) {
        if (greetingSentAt.isEmpty()) {
            return;
        }
        Set<String> heard = spokenTo(mind);
        for (var sent = greetingSentAt.entrySet().iterator(); sent.hasNext(); ) {
            var greeting = sent.next();
            String who = "npc:" + greeting.getKey();
            if (heard.contains(who)) {
                silences.remove(greeting.getKey());
                sent.remove();
            } else if (decisionsMade - greeting.getValue() > ANSWER_WITHIN) {
                sent.remove();
                if (silences.merge(greeting.getKey(), 1, Integer::sum) >= SILENT_AFTER) {
                    mind.saw(who, "does_not_answer", "true", tick);
                }
            }
        }
    }

    /** Everyone this agent has heard speak for itself, rather than been told about. */
    static Set<String> spokenTo(Mind mind) {
        Set<String> met = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("talks_in")
                    && belief.provenance() == Belief.Provenance.FIRST_HAND) {
                met.add(belief.subject());
            }
        }
        return met;
    }

    private void someoneToTalkTo(List<Choice> choices, WorldModel world, Mind mind, Point self) {
        worthTalkingTo(world, mind, self).ifPresent(npc -> {
            double distance = npc.position().distance(self);
            Optional<Integer> offer = QuestBoard.offeredBy(npc.typeId()).stream()
                    .filter(q -> !questsTried.contains(q))
                    .findFirst();
            // The NPC that named a price this agent can now pay is the one it came back
            // for, so neither "said hello recently" nor an ordinary quest marker should be
            // able to talk it out of the conversation it made the journey for.
            boolean cameBackFor = npc.typeId() == errandNpc;
            Integer greeted = greetedAt.get(npc.typeId());
            if (offer.isEmpty() && !cameBackFor
                    && greeted != null && decisionsMade - greeted < askAgainAfter()) {
                return;     // nothing new to say to this one, for now
            }
            // Something on offer is worth crossing a map for; a chat is worth a wander.
            double appeal = cameBackFor ? 1.0
                    : offer.isPresent() ? 0.7 : 0.2 + disposition.curiosity() * 0.3;
            double score = appeal + NEGLECT_MATTERS * neglect("talk") + urgeFor("talk")
                    + commitmentTo("talk", npc.position());

            if (!IntentExecutor.canSpeakTo(world.mapId(), self, npc.position(), NPC_RANGE)) {
                Point where = npc.position();
                choices.add(new Choice("talk", new Intent.MoveTo(npc.position()),
                        "go and see what that one wants", score,
                        () -> settingOff("talk", where)));
                return;
            }
            // Taking what somebody offers and hearing what they have to say are different
            // things, and the quest marker used to win both. Shanks offers quest 1028, so an
            // agent standing in front of him took the quest every time and never once opened
            // a conversation - which is where "do you want to go to Victoria Island? It costs
            // 150 mesos" lives. The model could read that perfectly and was never shown it.
            //
            // So somebody never heard from gets listened to first. The quest is still there
            // afterwards; the sentence might not be.
            // Having tried counts, even when nothing came back. Some NPCs never answer -
            // the server calls them "not coded" - so no conversation is ever recorded, and
            // insisting on one first meant an agent said hello to somebody who cannot speak
            // three hundred and eighty-five times in half an hour and did nothing else. The
            // quest kept them permanently worth asking; the silence kept them permanently
            // unheard. Say hello once, then take what they are offering.
            boolean neverHeardThemSpeak = !spokenTo(mind).contains("npc:" + npc.typeId())
                    && !greetedAt.containsKey(npc.typeId());
            // Unless it came back to talk. Going back to its trainer at level 30 is going back
            // to hear what comes next, and that is said in conversation; a trainer with a quest
            // on offer got the quest asked about instead, every time, and the letter that
            // starts a second job was never handed over.
            if (offer.isPresent() && !neverHeardThemSpeak && !cameBackFor) {
                choices.add(new Choice("talk",
                        new Intent.StartQuest(offer.get(), npc.typeId(), npc.position()),
                        "take whatever this one is offering", score,
                        () -> {
                            questsTried.add(offer.get());
                            spokeToSomeone();
                            arrived();
                        }));
                return;
            }
            choices.add(new Choice("talk",
                    new Intent.TalkTo(npc.objectId(), npc.typeId(), npc.position()),
                    "say hello and see what happens", score,
                    () -> {
                        greetedAt.put(npc.typeId(), decisionsMade);
                        greetingSentAt.putIfAbsent(npc.typeId(), decisionsMade);
                        spokeToSomeone();
                        arrived();
                    }));
        });
    }

    private void awayOutOfHere(List<Choice> choices, WorldModel world, Mind mind, Point self,
                               boolean stale) {
        // Picking a candidate is not the same as setting off for one. Committing here, while
        // merely scoring the option, meant the commitment bonus applied on the very first
        // decision an agent ever made and the door beat everything for the rest of its life -
        // seven tests said so at once.
        boolean alreadyOnTheWay = committedPortal != null;
        if (doorInMind == null) {
            doorInMind = chooseDoor(doorsWithinReach(world, self, mind), mind, world.mapId(),
                    "player:" + world.characterId());
        }
        WorldModel.PortalTarget door = alreadyOnTheWay ? committedPortal : doorInMind;
        if (door == null) {
            // Every door here has been tried and none of them did anything. An agent in a room
            // with no working way out should get on with what is in the room, not keep walking
            // into the walls - which is what fifty-two attempts at one tutorial portal looked
            // like from the outside.
            return;
        }

        // Wearing out a place makes the door more attractive; already being on the way makes it
        // much more so, because a door abandoned halfway is a door never reached. That was
        // learned the hard way: an agent once spent a two-minute run taking a single step
        // towards a door and then thinking better of it, over and over.
        // Scaled by how long the agent has been here, rather than offered at full strength on
        // arrival. Flat wanderlust made a door outscore a monster from the first decision in
        // every map, and the agent stopped fighting altogether: three minutes of measurement
        // came back 91% walking, 9% doors, and no combat whatsoever. A door is worth taking
        // when you have worn a place out, not the moment you get there.
        // No neglect term here, unlike every other option. Neglect asks "when did the agent
        // last do this", which is the right question for fighting or talking - things you do
        // over and over in one place - and the wrong one for leaving. An agent should walk
        // out because a map is spent, not because it has not walked out lately. With neglect
        // in, the door won every attention span regardless, and since reaching one costs
        // dozens of steps the agent spent 88% of its life walking to exits.
        // An errand is the one reason to leave that does not have to wait for a map to wear
        // out. Everything else here is a measure of boredom; this is the agent knowing where
        // it is going and what is waiting when it arrives.
        double wornOut = Math.min(1.0, (double) decisionsHere / disposition.patience());
        double boredom = (0.3 + disposition.wanderlust()) * wornOut
                + (stale ? 1.0 : 0)
                + urgeFor("door")
                + (alreadyOnTheWay ? COMMITTED : 0)
                // The model looked at every place the agent could go and picked one. That is a
                // decision about the next few minutes, and letting the nearest snail outvote it
                // made the model's choices lean rather than decide.
                + (destinationFromModel && destination != null ? COMMITTED : 0);
        // A quest to ask for or hand in, with the person right here, is not a map worn out,
        // however well the agent knows it. Mushroom Town is known by heart from birth, so it
        // was stale on arrival: a wanderer walked out past Heena without asking her for
        // anything, and another left carrying her quest with Sera, who finishes it, in sight.
        if (somethingToDoHere(world, mind)) {
            boredom = 0;
        }
        double score = boredom
                + (errandMap != null ? ERRAND : 0)
                // A map that has killed it twice at about this level is a map to get out of.
                + (deathsHere >= 2 ? LEAVE_A_KILLING_GROUND : 0);

        if (atPortal(self, door.position())) {
            choices.add(new Choice("door",
                    new Intent.EnterPortal(door.name(), door.position()),
                    "see where this goes", score,
                    () -> {
                        // putIfAbsent, not put. Walking into the same door again must not
                        // restart its clock, or a door tried every six decisions and judged
                        // after twelve is never judged at all - which is how one agent came
                        // to walk into the same tutorial portal fifty-two times while the
                        // machinery for noticing sat there working perfectly.
                        doorsAwaitingVerdict.putIfAbsent(portalRef(world.mapId(), door.name()),
                                decisionsMade);
                        lastDoorTriedAt = currentTick;
                        committedPortal = null;
                        doorInMind = null;      // used it; next time, choose afresh
                        arrived();
                    }));
            return;
        }
        // The door and how far off it is, in the goal: every diagnosis of this behaviour so
        // far has been inference from positions, because the trace could say which kind of
        // thing won but never which door or how distant.
        choices.add(new Choice("door", new Intent.MoveTo(door.position()),
                "walk to a way out: " + door.name() + " "
                        + Math.round(door.position().distance(self)) + "px away ["
                        + whyThisDoor + "]", score, () -> {
                    committedPortal = door;
                    // Register it as the journey too, not just as a committed portal. Two
                    // commitment mechanisms meant a door and a conversation could both be
                    // "under way" at once, each adding its bonus, which is the oscillation
                    // this was all meant to stop. settingOff overwrites, so there is exactly
                    // one thing an agent is in the middle of.
                    settingOff("door", door.position());
                }));
    }

    /**
     * The doors in this map the agent could actually walk to from where it stands, and has
     * not just given up trying to reach.
     *
     * Choosing among every door in the map meant choosing one it had no way to reach: it set
     * off, got nowhere, gave up - and chose the same door again, because nothing about the
     * choice had changed. An agent paced a ledge for as long as anyone watched while the
     * door option it kept rejecting scored 3.3 against wander's scraps.
     */
    private List<WorldModel.PortalTarget> doorsWithinReach(WorldModel world, Point self, Mind mind) {
        List<WorldModel.PortalTarget> reachable = world.portals().stream()
                .filter(door -> !outOfMind(door.position()))
                .filter(door -> atPortal(self, door.position())
                        || agents.world.Navigator.nextStep(world.mapId(), self, door.position()).isPresent())
                .toList();
        return reachable;
    }

    /**
     * Going back and forth between two maps: whichever rule proposes the door back, it is not
     * taken while there is another. The new generation's first ten minutes were three agents
     * crossing between Southperry and Split Road every second and a half.
     */
    private List<WorldModel.PortalTarget> notBackIfBouncing(List<WorldModel.PortalTarget> portals,
                                                            KnownWorld known, int mapId) {
        if (!bouncing() || cameFrom == null) {
            return portals;
        }
        List<WorldModel.PortalTarget> onward = portals.stream()
                .filter(door -> !known.destinationOf(KnownWorld.portalRef(mapId, door.name()))
                        .map(cameFrom::equals).orElse(false))
                .toList();
        // Only if one of the others goes anywhere. A dead door counted as a way on: the Hall
        // of Thieves has the way back and a door that leads nowhere, so bouncing once struck
        // off the way back, the dead door was struck off next, and all three agents wandered
        // their rooms for over an hour with no door at all.
        // Nor does a door into somewhere that keeps killing it, which is struck off too.
        boolean anyLeadsOn = onward.stream().anyMatch(door -> !known
                .destinationOf(KnownWorld.portalRef(mapId, door.name()))
                .map(to -> KnownWorld.NOWHERE.equals(to) || killingGrounds.contains(to)).orElse(false));
        return anyLeadsOn ? onward : portals;
    }

    /** The last few maps entered, to notice going back and forth. */
    private final java.util.ArrayDeque<Integer> recentMaps = new java.util.ArrayDeque<>();

    /** Whether the last four maps entered alternate between two. */
    boolean bouncing() {
        if (recentMaps.size() < 4) {
            return false;
        }
        Integer[] maps = recentMaps.toArray(new Integer[0]);
        int n = maps.length;
        return !maps[n - 1].equals(maps[n - 2]) && maps[n - 1].equals(maps[n - 3])
                && maps[n - 2].equals(maps[n - 4]);
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
        Set<String> waiting = waitingOnUs;
        return world.visibleNpcs().stream()
                // Somebody waiting for the agent to do what they asked is not somebody to
                // offer the quest to yet: Roger asks for his apple to be eaten first.
                .filter(npc -> !waiting.contains("npc:" + npc.typeId()))
                .flatMap(npc -> QuestBoard.endedBy(npc.typeId()).stream()
                        .filter(started::contains)
                        .filter(this::offWorriedCooldown)
                        .filter(questId -> !givenUpAtThisLevel(questId, world.level()))
                        .map(questId -> new Errand(questId, npc)))
                .min(Comparator.comparingInt(Errand::questId));
    }

    /**
     * Times each quest has been offered back without being taken, at the level it was last
     * offered at. Some quests are never finished by turning up - a medal that counts kills,
     * something wanting items the agent has never seen - and offering them back on a cooldown
     * had Agent2 walking to the same NPC hundreds of times an hour and nowhere else.
     */
    private final Map<Integer, int[]> handInsAtLevel = new HashMap<>();

    /** Offers back at one level before a quest waits for the agent to grow. */
    private static final int HAND_IN_TRIES = 3;

    private boolean givenUpAtThisLevel(int questId, int level) {
        int[] tried = handInsAtLevel.get(questId);
        return tried != null && tried[1] == level && tried[0] >= HAND_IN_TRIES;
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
    /** NPCs waiting for the agent to finish doing what they asked. */
    private Set<String> waitingOnUs = Set.of();

    static Set<String> waitingOn(Mind mind) {
        Set<String> waiting = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("waiting_on") && !belief.object().equals("nothing")) {
                waiting.add(belief.subject());
            }
        }
        return waiting;
    }

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
    public void rethink() {
        gaveUpOn.clear();
        doorsAwaitingVerdict.clear();
        doorInMind = null;
        committedPortal = null;
        destination = null;
        destinationFromModel = false;
        wanderingTo = null;
        recentMaps.clear();
        cameFrom = null;
        greetedAt.clear();
        arrived();
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
    /** Lets a test say where the agent walked in, which is otherwise set on a map change. */
    void cameInAt(Point where) {
        this.arrivedAt = where;
    }

    /** Visible for testing: how many doors are still waiting to be judged. */
    int doorsAwaitingVerdict() {
        return doorsAwaitingVerdict.size();
    }

    /** Visible for testing: which door the agent would set off for. */
    WorldModel.PortalTarget pickDoor(List<WorldModel.PortalTarget> portals, Mind mind, int mapId,
                                     String selfRef) {
        return chooseDoor(portals, mind, mapId, selfRef);
    }

    private WorldModel.PortalTarget chooseDoor(List<WorldModel.PortalTarget> portals, Mind mind,
                                               int mapId, String selfRef) {
        if (portals.isEmpty()) {
            return null;
        }
        KnownWorld known = KnownWorld.rememberedBy(mind.semantic().liveBeliefs());
        portals = notBackIfBouncing(portals, known, mapId);
        Set<String> beenThere = mapsVisited(mind);
        Set<String> companionsAre = companionMaps(mind, mapId, selfRef);

        List<WorldModel.PortalTarget> towardsCompany = new ArrayList<>();
        List<WorldModel.PortalTarget> untried = new ArrayList<>();
        List<WorldModel.PortalTarget> towardsSomewhereNew = new ArrayList<>();
        List<WorldModel.PortalTarget> worthTrying = new ArrayList<>();
        List<WorldModel.PortalTarget> intoDanger = new ArrayList<>();
        for (WorldModel.PortalTarget portal : portals) {
            Optional<String> leadsTo =
                    known.destinationOf(KnownWorld.portalRef(mapId, portal.name()));
            if (leadsTo.filter(NOWHERE::equals).isPresent()) {
                continue;       // tried it, nothing happened, not trying it again
            }
            // Nor into somewhere that keeps killing it, by any tier. Routing avoided it and the
            // last resort did not: with nothing better to do, the agent took the door it had
            // died beyond twelve times, and died there twice more.
            if (leadsTo.filter(killingGrounds::contains).isPresent()) {
                intoDanger.add(portal);
                continue;
            }
            worthTrying.add(portal);
            // Somewhere new is tested before company, so a door that is both counts as new.
            // The other way round, a companion standing in a map this agent had never seen
            // turned the most interesting door on the map into the lowest-ranked one.
            if (leadsTo.isEmpty()) {
                untried.add(portal);
            } else if (!beenThere.contains(leadsTo.get())) {
                towardsSomewhereNew.add(portal);
            } else if (companionsAre.contains(leadsTo.get())) {
                towardsCompany.add(portal);
            }
        }

        // The last resort is every door still worth trying, not every door there is. Falling
        // back to the full list handed the duds straight back, which is how an agent walked
        // into the same dead tutorial portal fifty-two times while believing it led nowhere.
        if (worthTrying.isEmpty() && !intoDanger.isEmpty() && strandedHere) {
            // Every way out on foot leads somewhere that killed it, and there is nothing here
            // to do. Through, then, rather than wait here for somebody to offer passage.
            whyThisDoor = "the only way on is through somewhere that killed you";
            return onwardOf(intoDanger);
        }
        if (worthTrying.isEmpty()) {
            return null;        // no way out of here that works; get on with what is here
        }

        // An unopened door in this very room beats any plan, because it is the cheapest
        // possible way to learn something and the plan would only be a longer way round to
        // an equivalent door.
        // Unless the model chose where to go: it was shown this room's doors and every place
        // worth going, and picked. An unopened door here is still there on the way back.
        if (!untried.isEmpty() && !(destinationFromModel && destination != null)) {
            whyThisDoor = "untried " + untried.size() + "/" + portals.size();
            return onwardOf(untried);
        }

        // Nothing unopened here. This is the moment three separate orderings of the tiers
        // below all failed at: every door in the room leads somewhere the agent has been, so
        // whichever it picks it is going round its own loop again. An agent spent hours like
        // this inside nine maps, one of which it had visited and left by the door it came in
        // - and the door it never opened there was the way off the island.
        //
        // So stop choosing between the doors in this room and ask a larger question: where
        // is the edge of what I know, and which way is it from here? The answer is a walk
        // several maps long, and only its first step is taken now; the rest is re-derived on
        // arrival, which is the honest way for something that learns as it walks to hold a
        // plan. An errand - an NPC that named a price this agent can now pay - outranks
        // curiosity, because it is the one journey with a known reward at the end of it.
        // Four reasons to travel, ordered by what each could change rather than by how
        // certain it is: an NPC whose price the agent can now pay, a door nobody has opened,
        // somebody it has never spoken to, and somewhere it remembers something living.
        //
        // Strangers outrank monsters because of the difference in what they can do for an
        // agent. Another snail is calories. A stranger might be holding the only way off the
        // island - which is literally the case here: both agents remembered NPCs in
        // Southperry, had never met the one who sells passage, and had no reason to go back
        // there, since nothing was unopened and no monsters were remembered in that map.
        String here = KnownWorld.mapRef(mapId);
        Optional<WorldModel.PortalTarget> towardsDestination =
                towardsDestination(known, here, portals, mind, mapId);
        if (towardsDestination.isPresent()) {
            return towardsDestination.get();
        }

        // No route either: the agent has opened every door it has ever seen, or the one it
        // wants is not in this room. Back to picking the least stale door here - but not
        // back into a room it has already seen all of. A shop entrance is a door that works
        // and leads somewhere it has been, which is exactly what the last resort settles
        // for, so an agent with nothing better to do would step into a shop, step out, and
        // do it again for hours. It did, in two different towns.
        List<WorldModel.PortalTarget> notBackIntoARoom = worthTrying.stream()
                .filter(portal -> known
                        .destinationOf(KnownWorld.portalRef(mapId, portal.name()))
                        .map(destination -> !known.isSpentRoom(destination))
                        .orElse(true))
                .toList();
        List<WorldModel.PortalTarget> lastResort =
                notBackIntoARoom.isEmpty() ? worthTrying : notBackIntoARoom;
        // And not straight back the way it came, while there is any other way. With nothing
        // worth going to, the last resort took the door it had just come through, and then did
        // the same from the other side: Sleepywood and the room beside it, back and forth.
        List<WorldModel.PortalTarget> notBack = lastResort.stream()
                .filter(portal -> cameFrom == null || !known
                        .destinationOf(KnownWorld.portalRef(mapId, portal.name()))
                        .map(cameFrom::equals).orElse(false))
                .toList();
        if (!notBack.isEmpty()) {
            lastResort = notBack;
        }
        List<WorldModel.PortalTarget> preferred = !towardsSomewhereNew.isEmpty()
                ? towardsSomewhereNew
                : !lastResort.isEmpty() ? lastResort : towardsCompany;
        whyThisDoor = (preferred == towardsSomewhereNew ? "somewhere new"
                : preferred == towardsCompany ? "company"
                : preferred == notBackIntoARoom ? "not a room again" : "last resort")
                + " " + preferred.size() + "/" + portals.size();
        return onwardOf(preferred);
    }

    /**
     * Somewhere along this floor to walk to, kept until reached or given up on.
     *
     * Carries on in the direction it was going, and turns round at the end of the floor
     * rather than at random - which is what makes it look like walking about rather than
     * pacing on the spot.
     */
    private Point wanderTarget(WorldModel world, Point self) {
        boolean arrived = wanderingTo != null && Math.abs(wanderingTo.x - self.x) < 8;
        boolean tooLong = decisionsMade - wanderingSince > WANDER_PATIENCE;
        if (wanderingTo != null && !arrived && !tooLong) {
            return wanderingTo;
        }
        int map = world.mapId();
        int west = agents.world.Navigator.floorEnd(map, self, -1).orElse(self.x - WANDER_STEP);
        int east = agents.world.Navigator.floorEnd(map, self, 1).orElse(self.x + WANDER_STEP);
        int room = wanderDirection > 0 ? east - self.x : self.x - west;
        if (room < WANDER_MIN / 2 || arrived && random.nextInt(4) == 0) {
            wanderDirection = -wanderDirection;     // the end of the floor, or a change of mind
        }
        int distance = WANDER_MIN + random.nextInt(WANDER_MAX - WANDER_MIN);
        int x = Math.max(west, Math.min(east, self.x + wanderDirection * distance));
        // At the floor's own height there, or a wander up a hill aimed underground.
        wanderingTo = new Point(x, agents.world.Navigator.heightAlongFloor(map, self, x).orElse(self.y));
        wanderingSince = decisionsMade;
        return wanderingTo;
    }

    /**
     * The first door towards wherever the agent is going, choosing somewhere if it is going
     * nowhere yet.
     *
     * A destination is kept until it is reached, until it has taken too long, or until the
     * way there stops being in this room. Only then is a new one chosen - which is what stops
     * each map re-arguing the journey.
     */
    private Optional<WorldModel.PortalTarget> towardsDestination(
            KnownWorld known, String here, List<WorldModel.PortalTarget> portals, Mind mind, int mapId) {
        if (destination != null && decisionsMade - destinationSince > DESTINATION_PATIENCE) {
            destination = null;
        }
        if (destination != null) {
            Optional<WorldModel.PortalTarget> door = firstDoorTo(known, here, destination, portals);
            if (door.isPresent()) {
                whyThisDoor = "heading for " + destination
                        + (destinationFromModel ? " (model's choice)" : "") + ": " + destinationWhy;
                return door;
            }
            destination = null;         // no longer a way there from here
        }
        for (Places.Place place : places(known, here, mind)) {
            Optional<WorldModel.PortalTarget> door = portals.stream()
                    .filter(portal -> portal.name().equals(place.firstDoor()))
                    .findFirst();
            if (door.isEmpty()) {
                continue;
            }
            setOffFor(place.map(), String.join(", ", place.reasons()), false);
            whyThisDoor = "heading for " + place.map() + ", " + place.hops() + " maps off: "
                    + destinationWhy;
            return door;
        }
        return Optional.empty();
    }

    private Optional<WorldModel.PortalTarget> firstDoorTo(KnownWorld known, String here, String to,
                                                           List<WorldModel.PortalTarget> portals) {
        // Around the places that keep killing it, not through them.
        KnownWorld.Hop around = known.reachableFrom(here, killingGrounds).get(to);
        // Only reachable through danger, when that is all there is.
        KnownWorld.Hop hop = around != null ? around : known.reachableFrom(here, Set.of()).get(to);
        if (hop == null || hop.firstDoor() == null) {
            return Optional.empty();
        }
        return portals.stream().filter(portal -> portal.name().equals(hop.firstDoor())).findFirst();
    }

    /** The map it was in before this one, so the last resort does not simply go back. */
    private String cameFrom;

    /** Maps not to pass through, as of this decision. */
    private Set<String> killingGrounds = Set.of();

    /** How many deaths it remembers in all, to notice a new one. */
    private int deathsRemembered = -1;

    private void setOffFor(String map, String why, boolean fromModel) {
        destination = map;
        destinationWhy = why;
        destinationFromModel = fromModel;
        destinationSince = decisionsMade;
    }

    private List<Places.Place> places(KnownWorld known, String here, Mind mind) {
        Map<String, Double> justBeen = new HashMap<>();
        reachedAsDestination.forEach((map, at) -> {
            double left = 1.0 - (double) (decisionsMade - at) / JUST_BEEN_FOR;
            if (left > 0) {
                justBeen.put(map, left);
            }
        });
        Set<String> avoid = new HashSet<>(strandedBy(mind));
        avoid.addAll(doesNotAnswer(mind));
        int level = levelOf(mind);
        Places.Facts facts = new Places.Facts(here, visitsTo(mind), errandMap,
                worthHearingAgain(known), avoid, justBeen, shopMaps, sentMap, sentWhy,
                deathsNearLevel(mind, level), level, huntedAt(mind));
        return Places.worthGoing(known, facts, weights(seekingACalling(mind)));
    }

    /**
     * What each reason to travel is worth to this agent.
     *
     * An errand first, a door nobody has opened next, then strangers, then the rest: the
     * order the rules used to be asked in, now as sizes rather than turns, so enough of a
     * lesser reason can outweigh a greater one and the whole lot is paid for by distance.
     * A fighter weighs a hunting ground far more heavily; a curious agent weighs strangers.
     */
    private Places.Weights weights(boolean seekingACalling) {
        return new Places.Weights(
                2.0,                                        // a door never opened
                1.2 + disposition.curiosity() * 0.6         // somebody never spoken to,
                        + (seekingACalling ? 2.0 : 0),      // who might be the one to train it
                0.8,                                        // somebody worth hearing again
                3.0,                                        // somebody owed a visit
                3.5,                                        // a shop, when the bag is full
                0.4 + disposition.aggression() * 1.2,       // something to hunt
                1.5,                                        // somewhere never stood in
                0.3,                                        // the less visited, the better
                0.35,                                       // each door of walking
                1.0,                                        // a room already seen all of
                1.0);                                       // just went there: wipes its reasons, for now
    }

    /**
     * Whether this is a beginner old enough to be taken on by somebody and not yet taken on.
     *
     * Such a character has one thing to find that it cannot find by fighting, and it is a
     * person: two agents reached levels 18 and 27 without a job because nothing in how they
     * chose where to go cared more about meeting people than it did at level one.
     */
    private static boolean seekingACalling(Mind mind) {
        String job = null;
        int level = 0;
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (!belief.subject().equals("self")) {
                continue;
            }
            if (belief.predicate().equals("job")) {
                job = belief.object();
            } else if (belief.predicate().equals("level")) {
                try {
                    level = Integer.parseInt(belief.object());
                } catch (NumberFormatException ignored) {
                    // not a number
                }
            }
        }
        return "0".equals(job) && level >= 10;
    }

    /**
     * Drops whatever it was doing when it died.
     *
     * The plan that killed it is not worth resuming: an agent revived in Sleepywood with a
     * sixth of its health and walked straight back down the tunnel it had died in, because
     * that was still the way to where it had been going - twelve times in ten minutes.
     */
    private void rethinkAfterDying(Map<String, Integer> deaths) {
        int total = deaths.values().stream().mapToInt(Integer::intValue).sum();
        if (deathsRemembered >= 0 && total > deathsRemembered) {
            destination = null;
            destinationFromModel = false;
            doorInMind = null;
            committedPortal = null;
            arrived();
        }
        deathsRemembered = total;
    }

    /** How many times the map it is in has killed it at about its level. */
    private int deathsHere;

    private static final double LEAVE_A_KILLING_GROUND = 1.5;

    /** Deaths that still count: within this many levels of where it is now. */
    private static final int DEATHS_COUNT_FOR = 5;

    /**
     * How many times each map has killed it at about the level it is now.
     *
     * Read from what it saw of its own deaths - "map:M killed_you_at_level 18" - so a map that
     * killed it as a level-8 beginner stops counting once it is level 13.
     */
    private static Map<String, Integer> deathsNearLevel(Mind mind, int level) {
        Map<String, Integer> deaths = new HashMap<>();
        if (level <= 0) {
            return deaths;
        }
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (!belief.predicate().equals("killed_you_at_level")) {
                continue;
            }
            try {
                int at = Integer.parseInt(belief.object());
                if (level - at < DEATHS_COUNT_FOR) {
                    deaths.merge(belief.subject(), belief.supportedBy().size(), Integer::sum);
                }
            } catch (NumberFormatException ignored) {
                // not a level
            }
        }
        return deaths;
    }

    /**
     * The level people were seen hunting each map at, from recordings of them playing: where
     * several sessions disagree, the average, weighted by how often each was seen.
     */
    private static Map<String, Integer> huntedAt(Mind mind) {
        Map<String, double[]> sums = new HashMap<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (!belief.predicate().equals("hunted_at_level")) {
                continue;
            }
            try {
                int at = Integer.parseInt(belief.object());
                int seen = belief.supportedBy().size();
                double[] sum = sums.computeIfAbsent(belief.subject(), map -> new double[2]);
                sum[0] += at * seen;
                sum[1] += seen;
            } catch (NumberFormatException ignored) {
                // not a level
            }
        }
        Map<String, Integer> levels = new HashMap<>();
        sums.forEach((map, sum) -> levels.put(map, (int) Math.round(sum[0] / sum[1])));
        return levels;
    }

    private static int levelOf(Mind mind) {
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.subject().equals("self") && belief.predicate().equals("level")) {
                try {
                    return Integer.parseInt(belief.object());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** Below this share of health with nothing to drink, it stops picking fights. */
    static final double HURT = 0.35;

    /** Below this it backs off even with something to drink, and lets the drinking catch up. */
    static final double BADLY_HURT = 0.2;

    /**
     * Stops fighting and backs away when hurt with nothing to heal with.
     *
     * Six deaths in two hours, all of them the same way: an agent at a fifth of its health,
     * carrying nothing to drink, walking up to the next monster because fighting was the
     * best-scoring thing in sight. Health comes back on its own to a character left alone,
     * so the thing to do is to be left alone: away from whatever is nearest, or, with nothing
     * near, standing still.
     */
    private void backOffWhenHurt(List<Choice> choices, WorldModel world, Point self) {
        if (world.hp() < 0 || world.maxHp() <= 0) {
            return;
        }
        double health = (double) world.hp() / world.maxHp();
        boolean nothingToDrink = world.inventory().known() && world.inventory().carried(agents.percept.Item.USE)
                .stream().noneMatch(item -> Survival.isDrinkable(item.itemId()));
        // Setting off hurt is walking into the next map's monsters hurt. Rest first - unless
        // this map is the one that keeps killing it, in which case leaving is the rest.
        boolean readyToTravel = health >= (nothingToDrink ? TRAVEL_UNAIDED : TRAVEL_WITH_POTIONS);
        if (!readyToTravel && deathsHere < Places.KILLING_GROUND) {
            choices.removeIf(choice -> choice.kind().equals("door"));
        }
        if (!(health < BADLY_HURT || (health < HURT && nothingToDrink))) {
            if (!readyToTravel && choices.stream().noneMatch(c -> !c.kind().equals("wander"))) {
                choices.add(new Choice("recover", new Intent.Wait(), "rest before setting off", RECOVER, null));
            }
            return;
        }
        choices.removeIf(choice -> choice.kind().equals("fight") || choice.kind().equals("loot"));
        Optional<WorldModel.Entity> threat = world.nearestMonster()
                .filter(monster -> monster.position().distance(self) < THREAT_RANGE);
        if (threat.isPresent()) {
            int away = self.x >= threat.get().position().x ? 1 : -1;
            choices.add(new Choice("recover",
                    new Intent.MoveTo(new Point(self.x + away * THREAT_RANGE, self.y)),
                    "back away from what is hurting me", RECOVER, null));
        } else {
            choices.add(new Choice("recover", new Intent.Wait(), "catch my breath", RECOVER, null));
        }
    }

    /** How healthy to be before taking a door: with nothing to drink, and with something. */
    static final double TRAVEL_UNAIDED = 0.7;
    static final double TRAVEL_WITH_POTIONS = 0.4;

    private static final int THREAT_RANGE = 300;
    private static final double RECOVER = 2.5;

    /** How many times this agent has walked into each map, counting every arrival. */
    private static Map<String, Integer> visitsTo(Mind mind) {
        Map<String, Integer> visits = new HashMap<>();
        for (Belief belief : mind.semantic().all()) {
            if (belief.subject().equals("self") && belief.predicate().equals("in_map")) {
                visits.merge(belief.object(), 1, Integer::sum);
            }
        }
        return visits;
    }

    /**
     * The places worth going, best first, for something slower and wider-eyed to choose from.
     *
     * The same list the reflexes pick from, so whatever the model is offered is somewhere the
     * agent knows the way to.
     */
    public List<Places.Place> placesWorthGoing(Mind mind, WorldModel world, int limit) {
        KnownWorld known = KnownWorld.rememberedBy(mind.semantic().liveBeliefs());
        String here = KnownWorld.mapRef(world.mapId());
        return places(known, here, mind).stream()
                .filter(place -> world.portals().stream()
                        .anyMatch(portal -> portal.name().equals(place.firstDoor())))
                .limit(limit)
                .toList();
    }

    /**
     * Sets off for a map the model chose, and holds to it until it arrives.
     *
     * The door in mind is dropped so the next decision picks the first door of this journey
     * rather than finishing a walk to a door that led somewhere else.
     */
    public void headFor(String map, String why) {
        setOffFor(map, why, true);
        doorInMind = null;
        committedPortal = null;
    }

    /** Where the agent is heading, if anywhere. */
    public Optional<String> destination() {
        return Optional.ofNullable(destination);
    }

    /**
     * Onward rather than back. Among equally good doors, the one furthest from where the
     * agent walked in is the one that keeps it going in the direction it was already
     * heading - which is all "explore outward" can honestly mean to something that has never
     * seen a map of the world. It is a tie-break and nothing more: it misreads a map entered
     * from the middle, and now that routing exists it is no longer carrying the whole weight
     * of the agent's sense of direction.
     */
    private WorldModel.PortalTarget onwardOf(List<WorldModel.PortalTarget> doors) {
        if (arrivedAt != null && doors.size() > 1) {
            return doors.stream()
                    .max(Comparator.comparingDouble(door -> door.position().distance(arrivedAt)))
                    .orElseThrow();
        }
        return doors.get(random.nextInt(doors.size()));
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

    /**
     * Looks through what NPCs have asked for and works out whether any of it is now payable.
     *
     * Re-read every decision rather than cached, because the two things that change the
     * answer - the agent's level and its purse - change without warning, and the whole point
     * is to notice the moment a condition that was out of reach stops being so.
     *
     * An NPC spoken to recently is skipped even when its price is met. Without that the
     * agent walks back the instant it is told something, is told the same thing again, and
     * walks back again; {@link #WORTH_ANOTHER_ASK} already encodes how long "recently"
     * should be for exactly this reason.
     */
    private void takeStockOfWhatIsOwed(Mind mind, WorldModel world, long tick) {
        errandMap = null;
        errandNpc = -1;
        int mesos = mesosHeld(mind);
        Set<String> stranders = strandedBy(mind);
        String here = KnownWorld.mapRef(world.mapId());
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (!belief.predicate().equals("wants_first")
                    || !belief.subject().startsWith("npc:")) {
                continue;
            }
            if (!canPay(belief.object(), world.level(), mesos, id -> world.inventory().count(id))
                    || !hasTheStats(belief.object(), world::stat)) {
                continue;
            }
            if (stranders.contains(belief.subject())) {
                continue;       // it will not accept anything from them, so there is no errand
            }
            int npcId = npcIdIn(belief.subject());
            Integer spokenAt = greetedAt.get(npcId);
            if (npcId < 0 || (spokenAt != null && decisionsMade - spokenAt < WORTH_ANOTHER_ASK)) {
                continue;
            }
            Optional<String> whereItWas = lastSeenIn(mind, belief.subject());
            if (whereItWas.isEmpty()) {
                continue;       // heard the condition, never saw where; nothing to walk to
            }
            errandNpc = npcId;
            if (!whereItWas.get().equals(here)) {
                errandMap = whereItWas.get();
            }
            return;             // one errand at a time; a plan you keep changing is not one
        }
        whereItWasSent(mind, world, here, tick);
    }

    /** Where somebody sent the agent, or its trainer when it has grown, and why. Null if nowhere. */
    private String sentMap;
    private String sentWhy;

    /** The last time the agent came to hold something it did not have, for going back to its trainer. */
    private long newThingSince = -1;
    private Set<Integer> held = Set.of();

    /** How long an instruction stays worth following. A few hours of play. */
    private static final long INSTRUCTION_LASTS = 20_000;

    /**
     * Follows the last instruction anyone gave it, and otherwise goes back to whoever trained it
     * once it has grown.
     *
     * An instruction is "npc:X sends_you_to map:M" or "... npc:P", written down from what an NPC
     * said. It holds until the agent has stood in that map, or spoken to that person, since
     * being told. The trainer is whoever it was talking to when its job changed: past level 30
     * in a first job, anything new since they last spoke - a level, something carried that was
     * not before - is a reason to go and see them. That is how a second job starts, and how it
     * ends, with the proof of the test in hand.
     */
    private void whereItWasSent(Mind mind, WorldModel world, String here, long now) {
        sentMap = null;
        sentWhy = null;
        noticeNewThings(world, now);

        Belief instruction = null;
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("sends_you_to") && belief.subject().startsWith("npc:")
                    && (instruction == null || belief.lastSeen() > instruction.lastSeen())) {
                instruction = belief;
            }
        }
        if (instruction != null && now - instruction.lastSeen() < INSTRUCTION_LASTS
                && !followed(mind, instruction.object(), instruction.lastSeen())) {
            String target = instruction.object();
            Optional<String> map = target.startsWith("map:") ? Optional.of(target) : lastSeenIn(mind, target);
            if (map.isPresent()) {
                if (target.startsWith("npc:")) {
                    errandNpc = npcIdIn(target);
                }
                if (!map.get().equals(here)) {
                    sentMap = map.get();
                    sentWhy = "where " + instruction.subject() + " sent you";
                }
                return;
            }
        }

        Belief trainedBy = null;
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.subject().equals("self") && belief.predicate().equals("trained_by")
                    && (trainedBy == null || belief.lastSeen() > trainedBy.lastSeen())) {
                trainedBy = belief;
            }
        }
        int job = world.job();
        if (trainedBy == null || job <= 0 || job % 100 != 0 || world.level() < 30) {
            return;
        }
        long grewAt = Math.max(levelReachedAt(mind), newThingSince);
        if (lastHeardFrom(mind, trainedBy.object()) >= grewAt) {
            return;         // nothing new to show them since they last spoke
        }
        Optional<String> map = lastSeenIn(mind, trainedBy.object());
        if (map.isEmpty()) {
            return;
        }
        errandNpc = npcIdIn(trainedBy.object());
        if (!map.get().equals(here)) {
            sentMap = map.get();
            sentWhy = "the one who trained you, and you have grown since";
        }
    }

    /** Whether an instruction heard at a tick has been carried out since. */
    private static boolean followed(Mind mind, String target, long heardAt) {
        if (target.startsWith("map:")) {
            for (Belief belief : mind.semantic().all()) {
                if (belief.subject().equals("self") && belief.predicate().equals("in_map")
                        && belief.object().equals(target) && belief.lastSeen() >= heardAt) {
                    return true;
                }
            }
            return false;
        }
        return lastHeardFrom(mind, target) >= heardAt;
    }

    /** When this NPC last spoke to the agent itself, or -1. */
    private static long lastHeardFrom(Mind mind, String npc) {
        long last = -1;
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.subject().equals(npc) && belief.predicate().equals("talks_in")
                    && belief.provenance() == Belief.Provenance.FIRST_HAND) {
                last = Math.max(last, belief.lastSeen());
            }
        }
        return last;
    }

    /** When the agent reached the level it is at now. */
    private static long levelReachedAt(Mind mind) {
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.subject().equals("self") && belief.predicate().equals("level")) {
                return belief.firstSeen();
            }
        }
        return -1;
    }

    /** Keeps the tick at which something new last turned up in its bags. */
    private void noticeNewThings(WorldModel world, long now) {
        if (!world.inventory().known()) {
            return;
        }
        Set<Integer> carried = new HashSet<>();
        for (int type = agents.percept.Item.EQUIP; type <= agents.percept.Item.ETC; type++) {
            for (agents.percept.Item item : world.inventory().carried(type)) {
                carried.add(item.itemId());
            }
        }
        if (!held.isEmpty() && !held.containsAll(carried)) {
            newThingSince = now;
        }
        held = carried;
    }

    /**
     * Whether what an NPC asked for is something the agent now has.
     *
     * The condition is free text, because it came back from a model reading the NPC's own
     * words, so this reads what it can out of it and takes silence for consent. An
     * unparseable condition means the agent goes back and asks - which is what a player does
     * when they half-remember being told to come back later, and costs one conversation.
     */
    /**
     * Whether what was asked for is in hand, items included: "30 item:4031013" is thirty of
     * that item, counted in the bags.
     */
    static boolean canPay(String asked, int level, int mesos, java.util.function.IntUnaryOperator itemCount) {
        Matcher wantsItems = ITEMS_ASKED.matcher(asked);
        while (wantsItems.find()) {
            if (itemCount.applyAsInt(Integer.parseInt(wantsItems.group(2))) < Integer.parseInt(wantsItems.group(1))) {
                return false;
            }
        }
        return canPay(asked, level, mesos);
    }

    private static final Pattern ITEMS_ASKED = Pattern.compile("(\\d+) item:(\\d+)");

    /**
     * Whether the agent has the stats a condition names: "level 10, DEX 25" is not met at
     * level ten with four DEX, and going back to ask at level ten anyway is a walk back to the
     * Bowman's statue every few minutes for a fighter that will never have the DEX.
     */
    static boolean hasTheStats(String asked, java.util.function.ToIntFunction<String> stat) {
        Matcher wants = STAT_ASKED.matcher(asked);
        while (wants.find()) {
            int have = stat.applyAsInt(wants.group(1).toUpperCase());
            if (have >= 0 && have < Integer.parseInt(wants.group(2))) {
                return false;
            }
        }
        return true;
    }

    private static final Pattern STAT_ASKED = Pattern.compile("\\b(STR|DEX|INT|LUK)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    static boolean canPay(String asked, int level, int mesos) {
        // Only a condition it can check is one it can meet. Taking an unreadable one for met
        // sent agents back, every few minutes, to anybody the model had noted as wanting
        // "class selection options" or "No specific requirement stated" - Agent1 walked to
        // Southperry thirteen times in an hour - and never once got anything for it.
        if (!isConcrete(asked)) {
            return false;
        }
        Matcher wantsLevel = LEVEL_ASKED.matcher(asked);
        if (wantsLevel.find() && level < Integer.parseInt(wantsLevel.group(1))) {
            return false;
        }
        Matcher wantsMesos = MESOS_ASKED.matcher(asked);
        return !wantsMesos.find()
                || mesos >= Integer.parseInt(wantsMesos.group(1).replace(",", ""));
    }

    /** A level, a sum of mesos or a number of an item: something the agent can count. */
    public static boolean isConcrete(String asked) {
        return LEVEL_ASKED.matcher(asked).find() || MESOS_ASKED.matcher(asked).find()
                || ITEMS_ASKED.matcher(asked).find();
    }

    private static final Pattern LEVEL_ASKED =
            Pattern.compile("(?:level|lv\\.?)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESOS_ASKED =
            Pattern.compile("([\\d,]+)\\s*mesos?", Pattern.CASE_INSENSITIVE);

    /** What the agent last knew about its own purse, which the server tells it as a stat. */
    private static int mesosHeld(Mind mind) {
        return mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals("self") && b.predicate().equals("meso"))
                .map(Belief::object)
                .findFirst()
                .map(held -> {
                    try {
                        return Integer.parseInt(held);
                    } catch (NumberFormatException notANumber) {
                        return 0;
                    }
                })
                .orElse(0);
    }

    /** The map an NPC was last perceived in, whether by seeing it or by talking to it. */
    private static Optional<String> lastSeenIn(Mind mind, String npcRef) {
        return mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals(npcRef)
                        && (b.predicate().equals("present_in") || b.predicate().equals("talks_in")))
                .map(Belief::object)
                .findFirst();
    }

    private static int npcIdIn(String npcRef) {
        try {
            return Integer.parseInt(npcRef.substring("npc:".length()));
        } catch (NumberFormatException notAnId) {
            return -1;
        }
    }

    /** Where a door goes when walking into it does nothing at all. */
    private static final String NOWHERE = KnownWorld.NOWHERE;

    private static String portalRef(int mapId, String name) {
        return KnownWorld.portalRef(mapId, name);
    }
}
