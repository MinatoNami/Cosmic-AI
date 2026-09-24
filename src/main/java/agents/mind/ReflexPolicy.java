package agents.mind;

import agents.Mind;
import agents.memory.Belief;
import agents.world.KnownWorld;
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
    private static final int WANDER_STEP = 80;
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
            if (!doorsAwaitingVerdict.isEmpty() && lastMapId >= 0) {
                // Whichever door was tried most recently is the one that worked; the rest were
                // tried from a map we are no longer in and can never be judged now.
                String worked = doorsAwaitingVerdict.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                mind.infer(worked, "leads_to", "map:" + world.mapId(), tick);
                doorsAwaitingVerdict.clear();
            }
            lastMapId = world.mapId();
            decisionsHere = 0;
            decisionsSinceProgress = 0;
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

        if (world.level() > lastLevel) {
            lastLevel = world.level();
            decisionsSinceProgress = 0;
        } else {
            decisionsSinceProgress++;
        }

        takeStockOfWhatIsOwed(mind, world);

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
        someoneToTalkTo(choices, world, mind, self);
        awayOutOfHere(choices, world, mind, self, stale);
        choices.add(new Choice("wander", new Intent.MoveTo(
                new Point(self.x + random.nextInt(2 * WANDER_STEP) - WANDER_STEP, self.y)),
                "wander", WANDERING_IS_BETTER_THAN_NOTHING, null));

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
            if (distance >= NPC_RANGE) {
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
                        spokeToSomeone();
                        arrived();
                    }));
        });
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
    private static Optional<WorldModel.Entity> worthTalkingTo(WorldModel world, Mind mind,
                                                              Point self) {
        Set<String> met = spokenTo(mind);
        Comparator<WorldModel.Entity> strangersFirst = Comparator.comparingInt(
                npc -> met.contains("npc:" + npc.typeId()) ? 1 : 0);
        return world.visibleNpcs().stream()
                .min(strangersFirst.thenComparingDouble(
                        npc -> npc.position().distance(self)));
    }

    /** Everyone this agent has heard speak for itself, rather than been told about. */
    private static Set<String> spokenTo(Mind mind) {
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
                    && greeted != null && decisionsMade - greeted < WORTH_ANOTHER_ASK) {
                return;     // nothing new to say to this one, for now
            }
            // Something on offer is worth crossing a map for; a chat is worth a wander.
            double appeal = cameBackFor ? 1.0
                    : offer.isPresent() ? 0.7 : 0.2 + disposition.curiosity() * 0.3;
            double score = appeal + NEGLECT_MATTERS * neglect("talk") + urgeFor("talk")
                    + commitmentTo("talk", npc.position());

            if (distance >= NPC_RANGE) {
                Point where = npc.position();
                choices.add(new Choice("talk", new Intent.MoveTo(npc.position()),
                        "go and see what that one wants", score,
                        () -> settingOff("talk", where)));
                return;
            }
            if (offer.isPresent()) {
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
            doorInMind = chooseDoor(world.portals(), mind, world.mapId(),
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
        double score = (0.3 + disposition.wanderlust()) * wornOut
                + (stale ? 1.0 : 0)
                + (errandMap != null ? ERRAND : 0)
                + urgeFor("door")
                + (alreadyOnTheWay ? COMMITTED : 0);

        if (door.position().distance(self) < PORTAL_RANGE) {
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
        Set<String> beenThere = mapsVisited(mind);
        Set<String> companionsAre = companionMaps(mind, mapId, selfRef);

        List<WorldModel.PortalTarget> towardsCompany = new ArrayList<>();
        List<WorldModel.PortalTarget> untried = new ArrayList<>();
        List<WorldModel.PortalTarget> towardsSomewhereNew = new ArrayList<>();
        List<WorldModel.PortalTarget> worthTrying = new ArrayList<>();
        for (WorldModel.PortalTarget portal : portals) {
            Optional<String> leadsTo =
                    known.destinationOf(KnownWorld.portalRef(mapId, portal.name()));
            if (leadsTo.filter(NOWHERE::equals).isPresent()) {
                continue;       // tried it, nothing happened, not trying it again
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
        if (worthTrying.isEmpty()) {
            return null;        // no way out of here that works; get on with what is here
        }

        // An unopened door in this very room beats any plan, because it is the cheapest
        // possible way to learn something and the plan would only be a longer way round to
        // an equivalent door.
        if (!untried.isEmpty()) {
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
        Optional<KnownWorld.Route> route = Optional.ofNullable(errandMap)
                .flatMap(target -> known.routeTo(here, target))
                .or(() -> known.routeToNearestFrontier(here))
                .or(() -> known.routeToStrangers(here))
                .or(() -> known.routeToMonsters(here));
        Optional<WorldModel.PortalTarget> planned = route.flatMap(plan -> portals.stream()
                .filter(portal -> portal.name().equals(plan.firstDoor()))
                .findFirst());
        if (planned.isPresent()) {
            KnownWorld.Route plan = route.orElseThrow();
            whyThisDoor = plan.why() + " " + plan.hops() + " maps off";
            return planned.get();
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
    private void takeStockOfWhatIsOwed(Mind mind, WorldModel world) {
        errandMap = null;
        errandNpc = -1;
        int mesos = mesosHeld(mind);
        String here = KnownWorld.mapRef(world.mapId());
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (!belief.predicate().equals("wants_first")
                    || !belief.subject().startsWith("npc:")) {
                continue;
            }
            if (!canPay(belief.object(), world.level(), mesos)) {
                continue;
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
    }

    /**
     * Whether what an NPC asked for is something the agent now has.
     *
     * The condition is free text, because it came back from a model reading the NPC's own
     * words, so this reads what it can out of it and takes silence for consent. An
     * unparseable condition means the agent goes back and asks - which is what a player does
     * when they half-remember being told to come back later, and costs one conversation.
     */
    static boolean canPay(String asked, int level, int mesos) {
        Matcher wantsLevel = LEVEL_ASKED.matcher(asked);
        if (wantsLevel.find() && level < Integer.parseInt(wantsLevel.group(1))) {
            return false;
        }
        Matcher wantsMesos = MESOS_ASKED.matcher(asked);
        return !wantsMesos.find()
                || mesos >= Integer.parseInt(wantsMesos.group(1).replace(",", ""));
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
