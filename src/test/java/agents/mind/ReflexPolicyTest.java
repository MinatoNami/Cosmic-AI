package agents.mind;

import agents.Mind;
import agents.percept.Observation;
import agents.trace.Trace;
import agents.world.KnownWorld;
import agents.world.WorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflexPolicyTest {

    @TempDir
    Path traceDir;

    private Mind mind;
    private WorldModel world;
    private final Policy policy = new ReflexPolicy(new Random(1), Disposition.FIGHTER);

    @BeforeEach
    void setUp() {
        mind = new Mind("Test", Trace.toFile(traceDir.resolve("t.jsonl"), "Test"));
        world = new WorldModel();
        world.update(new Observation.MapEntered(1, 10000, 0));
        world.movedTo(new Point(0, 0));
    }

    @Test
    void hitsWhatIsWithinReach() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(20, 0)));

        Intent intent = policy.decide(mind, world, 2).intent();

        assertEquals(9001, assertInstanceOf(Intent.Attack.class, intent).objectId());
    }

    @Test
    void walksTowardsWhatIsTooFarToHit() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(400, 0)));

        Intent intent = policy.decide(mind, world, 2).intent();

        assertEquals(new Point(400, 0), assertInstanceOf(Intent.MoveTo.class, intent).destination());
    }

    /**
     * The whole point of dispositions: put the same monster in front of two agents and they
     * do different things, so they end up knowing different things.
     */
    @Test
    void aWandererWillNotCrossTheMapForAFight() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(400, 0)));
        Policy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);

        Intent intent = wanderer.decide(mind, world, 2).intent();

        // Where it goes instead is its own business - wandering, or off to a door. The claim
        // is only that it does not cross the map for a fight. It used to have to be a wander,
        // because nothing else could win; now heading for the exit is an honest answer too.
        Point destination = assertInstanceOf(Intent.MoveTo.class, intent).destination();
        org.junit.jupiter.api.Assertions.assertNotEquals(new Point(400, 0), destination,
                "a wanderer has better things to do than chase something that far away");
    }

    @Test
    void picksUpWhatIsUnderfootBeforeFighting() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(20, 0)));
        world.update(new Observation.DropAppeared(3, 9500, 2000000, false, new Point(5, 0)));

        Intent intent = policy.decide(mind, world, 3).intent();

        assertEquals(9500, assertInstanceOf(Intent.PickUp.class, intent).objectId());
    }

    /**
     * The agent reads its own beliefs to know what it owes, and goes back to offer without
     * any idea what the quest asked for. Quest 1031 is ended by npc 2100.
     */
    @Test
    void goesBackToHandInAQuestItStarted() {
        mind.take(new Observation.QuestStateChanged(2, 1031, 1));
        world.update(new Observation.NpcAppeared(3, 7001, 2100, new Point(20, 0)));

        Intent intent = new ReflexPolicy(new Random(1), Disposition.TALKER)
                .decide(mind, world, 3).intent();

        Intent.CompleteQuest handIn = assertInstanceOf(Intent.CompleteQuest.class, intent);
        assertEquals(1031, handIn.questId());
        assertEquals(2100, handIn.npcId());
    }

    @Test
    void doesNotHandInToAnNpcWhoCannotTakeIt() {
        mind.take(new Observation.QuestStateChanged(2, 1031, 1));
        // 2101 gives quest 1031 out; 2100 is the one who takes it back.
        world.update(new Observation.NpcAppeared(3, 7002, 2101, new Point(20, 0)));

        Intent intent = new ReflexPolicy(new Random(1), Disposition.TALKER)
                .decide(mind, world, 3).intent();

        org.junit.jupiter.api.Assertions.assertFalse(intent instanceof Intent.CompleteQuest);
    }

    @Test
    void doesNotKeepOfferingTheSameQuestEveryTick() {
        mind.take(new Observation.QuestStateChanged(2, 1031, 1));
        world.update(new Observation.NpcAppeared(3, 7001, 2100, new Point(20, 0)));
        Policy talker = new ReflexPolicy(new Random(1), Disposition.TALKER);

        assertInstanceOf(Intent.CompleteQuest.class, talker.decide(mind, world, 3).intent());

        org.junit.jupiter.api.Assertions.assertFalse(
                talker.decide(mind, world, 4).intent() instanceof Intent.CompleteQuest,
                "offering the same thing every tick is pestering, not persistence");
    }

    @Test
    void wandersWhenThereIsNothingToDo() {
        Intent intent = policy.decide(mind, world, 1).intent();

        assertInstanceOf(Intent.MoveTo.class, intent);
    }

    /**
     * The policy is the control condition, so it matters that it is not secretly clever: it
     * attacks what is near because it is near, not because it has any notion that monsters
     * are worth killing.
     */
    @Test
    void statesAGoalThatClaimsNoUnderstanding() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(20, 0)));

        Policy.Decision decision = policy.decide(mind, world, 2);

        assertEquals("hit what is in front of me", decision.goal());
        // considered used to be the fixed list of intents the policy knows about, which told a
        // reader nothing. It now holds what else was actually on the table and what each was
        // worth, which is the thing the field was added for.
        assertFalse(decision.considered().isEmpty(), "the trace should say what else was going");
        assertTrue(decision.considered().stream().anyMatch(option -> option.contains("=")),
                "each option should carry its score: " + decision.considered());
    }

    /**
     * The starvation this exists to prevent: loot and monsters sit above NPCs, quests and
     * doors in the ladder and feed each other - killing a monster makes a drop, and a drop
     * outranks a monster - so ninety seconds of a real agent came to eighty-eight per cent
     * fighting and looting, and it never spoke to anybody.
     */
    @Test
    void looksUpFromFightingOftenEnoughToDoSomethingElse() {
        // A monster permanently within reach - the situation that starves everything else -
        // and an NPC standing there as something else worth doing. The NPC matters: an agent
        // alone in a field with one monster and nothing else should fight, and an earlier
        // version of this test demanded it "look up" into an empty room, which is fidgeting
        // rather than behaviour.
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(20, 0)));
        world.update(new Observation.NpcAppeared(2, 8001, 2100, new Point(60, 0)));
        Policy grinder = new ReflexPolicy(new Random(1), Disposition.FIGHTER);

        int grinding = 0;
        int lookedUp = 0;
        for (int decision = 0; decision < Disposition.FIGHTER.attentionSpan() * 3; decision++) {
            Policy.Decision made = grinder.decide(mind, world, decision);
            if (made.intent() instanceof Intent.Attack || made.intent() instanceof Intent.PickUp) {
                grinding++;
            } else {
                lookedUp++;
            }
            // The world has to move when the agent does, or a journey can never end. Now that
            // setting off for something commits an agent to it, a test that leaves it rooted
            // to the spot measures an agent walking to an NPC it can never reach.
            if (made.intent() instanceof Intent.MoveTo going) {
                world.movedTo(going.destination());
            }
        }

        assertTrue(grinding > lookedUp, "fighting should still be what a fighter mostly does");
        assertTrue(lookedUp > 0,
                "it never did anything but fight in " + (Disposition.FIGHTER.attentionSpan() * 3)
                        + " decisions, with an NPC standing right there");
    }


    /**
     * An agent that walked in from the west should try the eastern door first.
     *
     * Choosing at random among unopened doors made each map a coin flip: one agent reached
     * Split Road of Destiny, one door from Southperry and the way off Maple Island, then
     * turned round and went back. Knowing where you came in is the only sense of direction
     * something without a map can honestly have.
     */
    @Test
    void headsOnwardRatherThanBackTheWayItCame() {
        // Positions only, no destinations known, so both doors are equally unopened.
        WorldModel.PortalTarget back = new WorldModel.PortalTarget("west00", new Point(-500, 0));
        WorldModel.PortalTarget onward = new WorldModel.PortalTarget("east00", new Point(900, 0));

        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        policy.cameInAt(new Point(-480, 0));

        WorldModel.PortalTarget chosen =
                policy.pickDoor(List.of(back, onward), mind, 10000, "player:1");

        assertEquals("east00", chosen.name(),
                "it walked in beside west00, so east00 is the one that leads onward");
    }

    /**
     * Every door in this room has been opened and leads somewhere the agent has been. The
     * thing it needs to remember is two maps away, and nothing in front of it says so.
     *
     * This is the stall that ran for hours: nine maps, every known exit leading back into
     * them, and one map - visited once and left by the door it came in - still holding an
     * unopened door that was the way off the island. Picking the best door in the room
     * cannot fix that, however the preferences are ranked, because the door it wants is not
     * in the room.
     */
    @Test
    void setsOffForADoorItNeverOpenedInAnotherMap() {
        mind.take(new Observation.MapEntered(1, 20000, 0));
        mind.take(new Observation.MapEntered(2, 30000, 0));
        mind.take(new Observation.MapEntered(3, 10000, 0));
        mind.infer(KnownWorld.portalRef(10000, "west00"), "leads_to",
                KnownWorld.mapRef(20000), 4);
        mind.infer(KnownWorld.portalRef(10000, "east00"), "leads_to",
                KnownWorld.mapRef(30000), 4);
        mind.saw(KnownWorld.mapRef(30000), "has_door", "north00", 4);

        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        // Walked in beside east00, so the sense-of-direction tie-break on its own would send
        // it west. Only the route knows better.
        policy.cameInAt(new Point(500, 0));

        WorldModel.PortalTarget chosen = policy.pickDoor(
                List.of(new WorldModel.PortalTarget("west00", new Point(-500, 0)),
                        new WorldModel.PortalTarget("east00", new Point(500, 0))),
                mind, 10000, "player:1");

        assertEquals("east00", chosen.name(),
                "the only unopened door it knows of is through east00, one map along");
    }

    /**
     * An NPC named a price. The agent could not pay it, went away, levelled up, and now can.
     *
     * The belief was already being written down and nothing read it, so the one instruction
     * that leads off Maple Island was recorded faithfully and then ignored forever.
     */
    @Test
    void goesBackToAnNpcOnceItCanPayWhatItAsked() {
        mind.take(new Observation.MapEntered(1, 20000, 0));
        mind.take(new Observation.MapEntered(2, 30000, 0));
        mind.take(new Observation.MapEntered(3, 10000, 0));
        mind.take(new Observation.StatsChanged(4, Map.of("LEVEL", 12, "MESO", 900)));
        mind.hear("npc:22000", "wants_first", "be level 6 and bring 150 mesos", 5);
        mind.infer("npc:22000", "present_in", KnownWorld.mapRef(20000), 5);
        mind.infer(KnownWorld.portalRef(10000, "west00"), "leads_to",
                KnownWorld.mapRef(20000), 5);
        mind.infer(KnownWorld.portalRef(10000, "east00"), "leads_to",
                KnownWorld.mapRef(30000), 5);
        mind.saw(KnownWorld.mapRef(30000), "has_door", "north00", 5);

        world.update(new Observation.MapEntered(6, 10000, 0));
        world.update(new Observation.StatsChanged(6, Map.of("LEVEL", 12)));

        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        policy.decide(mind, world, 6);          // notices what it is owed
        // Walked in beside west00, so both the direction tie-break and the unopened door it
        // knows of point east. Only the errand points back the other way.
        policy.cameInAt(new Point(-500, 0));

        WorldModel.PortalTarget chosen = policy.pickDoor(
                List.of(new WorldModel.PortalTarget("west00", new Point(-500, 0)),
                        new WorldModel.PortalTarget("east00", new Point(500, 0))),
                mind, 10000, "player:1");

        assertEquals("west00", chosen.name(),
                "the errand is west; the unopened door east is the more interesting trip "
                        + "and it should still lose to a conversation it can now afford");
    }

    @Test
    void willNotSetOffForAnNpcItStillCannotAfford() {
        mind.take(new Observation.MapEntered(1, 20000, 0));
        mind.take(new Observation.MapEntered(2, 10000, 0));
        mind.hear("npc:22000", "wants_first", "come back at level 30", 3);
        mind.infer("npc:22000", "present_in", KnownWorld.mapRef(20000), 3);

        world.update(new Observation.StatsChanged(4, Map.of("LEVEL", 12)));

        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        policy.decide(mind, world, 4);

        assertFalse(policy.decide(mind, world, 5).goal().contains("errand"),
                "level 12 is not level 30, so there is nothing to go back for yet");
    }

    @Test
    void readsAPriceOutOfWhateverTheNpcActuallySaid() {
        assertTrue(ReflexPolicy.canPay("you must be level 6 and have 150 mesos", 10, 200));
        assertFalse(ReflexPolicy.canPay("you must be level 6 and have 150 mesos", 3, 200));
        assertFalse(ReflexPolicy.canPay("you must be level 6 and have 150 mesos", 10, 20));
        assertTrue(ReflexPolicy.canPay("1,500 mesos", 1, 2000));
        assertFalse(ReflexPolicy.canPay("1,500 mesos", 1, 900));
    }

    /**
     * Taking silence for consent, on purpose: the condition is free text from a model
     * reading an NPC, and an unreadable one should send the agent back to ask rather than
     * write the NPC off. One wasted conversation is cheaper than a missed way out.
     */
    @Test
    void goesAndAsksAgainWhenItCannotTellWhatWasAskedFor() {
        assertTrue(ReflexPolicy.canPay("prove yourself worthy first", 1, 0));
    }

    /**
     * Two agents wiped clean landed in Lith Harbor, recorded all twenty-eight of its exits,
     * tried none of them, and spent fifteen minutes walking at one NPC standing on a ledge
     * they cannot climb to. Hundreds of MoveTo to the same point, the commitment bonus
     * winning the argument every time, and no mechanism anywhere for changing its mind.
     */
    @Test
    void givesUpOnSomewhereItCannotGetTo() {
        Point unreachable = new Point(2000, 0);
        world.update(new Observation.NpcAppeared(2, 7001, 2100, unreachable));
        Policy policy = new ReflexPolicy(new Random(1), Disposition.TALKER);

        boolean stillWalkingAtIt = false;
        for (int decision = 0; decision < 60; decision++) {
            Intent intent = policy.decide(mind, world, decision).intent();
            world.movedTo(new Point(0, 0));     // every step leaves it exactly where it was
            if (decision >= 45) {
                stillWalkingAtIt |= intent instanceof Intent.MoveTo going
                        && going.destination().equals(unreachable);
            }
        }

        assertFalse(stillWalkingAtIt,
                "after fifteen decisions that got it no closer it should have given up");
    }

    /**
     * An NPC that moves you must not get a door blamed for it.
     *
     * NPC 2007 stands a few steps from where every new character appears and asks "would you
     * like to skip the tutorials and head straight to Lith Harbor?". An agent said yes,
     * crossed an ocean, and credited the journey to portal glBmsg1 - which the map data
     * gives no destination and no script, and which cannot move anybody anywhere. The belief
     * graph gained a road that was never there, and routing plans along those.
     */
    @Test
    void willNotBlameADoorForAJourneyAnNpcGaveIt() {
        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        for (int decision = 0; decision < 400 && policy.doorsAwaitingVerdict() == 0; decision++) {
            Intent intent = policy.decide(mind, world, decision).intent();
            if (intent instanceof Intent.MoveTo going) {
                world.movedTo(going.destination());     // let it actually get there
            }
        }
        assertTrue(policy.doorsAwaitingVerdict() > 0,
                "it walked into a door, so it should be waiting to see what that did");

        // Someone turns up and it says hello. Whatever moves the agent after this, the door
        // it touched a moment ago is no longer the obvious culprit.
        world.update(new Observation.NpcAppeared(50, 7001, 2100, world.selfPosition()));
        for (int decision = 400; decision < 800 && policy.doorsAwaitingVerdict() > 0; decision++) {
            Intent intent = policy.decide(mind, world, decision).intent();
            if (intent instanceof Intent.MoveTo going) {
                world.movedTo(going.destination());
            }
        }

        assertEquals(0, policy.doorsAwaitingVerdict(),
                "talking to someone who could move you voids the door's pending verdict");
    }

    /** The other half: a journey that is working must not be called off for being long. */
    @Test
    void keepsGoingWhileItIsStillGettingCloser() {
        Point acrossTheMap = new Point(3000, 0);
        world.update(new Observation.NpcAppeared(2, 7001, 2100, acrossTheMap));
        Policy policy = new ReflexPolicy(new Random(1), Disposition.TALKER);

        Intent intent = null;
        Point walked = new Point(0, 0);
        for (int decision = 0; decision < 30; decision++) {
            intent = policy.decide(mind, world, decision).intent();
            walked = new Point(walked.x + 75, 0);   // one honest walking step
            world.movedTo(walked);
        }

        assertEquals(acrossTheMap,
                assertInstanceOf(Intent.MoveTo.class, intent).destination(),
                "it was getting closer every decision; there was nothing wrong with it");
    }

    /**
     * A shop is a room, not a place: one way out, one shopkeeper, nothing to find.
     *
     * Generation one shuttled between Lith Harbor and four of its shop interiors for hours;
     * generation two did the same between Southperry and its armoury. The waste is what
     * happens *after* the shopkeeper has been dealt with - talking goes quiet for a while,
     * and with nothing else in the room the agent falls back to wandering the furniture.
     * Indoors it should take the door instead, because the door is the only other thing
     * there is.
     */
    @Test
    void leavesAShopOnceItHasDealtWithTheShopkeeper() {
        WorldModel shop = new WorldModel();
        shop.update(new Observation.MapEntered(1, 1000001, 0));
        shop.movedTo(new Point(0, 0));
        shop.update(new Observation.NpcAppeared(2, 7001, 2100, new Point(20, 0)));
        assertEquals(1, shop.portals().size(),
                "a shop interior has exactly one usable portal, which is what makes it a room");

        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        int wandered = 0;
        int headedOut = 0;
        for (int decision = 0; decision < 120; decision++) {
            Policy.Decision made = wanderer.decide(mind, shop, decision);
            if (made.goal().startsWith("wander")) {
                wandered++;
            }
            if (made.goal().contains("way out") || made.intent() instanceof Intent.EnterPortal) {
                headedOut++;
            }
            if (made.intent() instanceof Intent.MoveTo going) {
                shop.movedTo(going.destination());
            }
        }

        assertTrue(headedOut > wandered,
                "in a room with one door it should be leaving, not milling about: "
                        + headedOut + " towards the door against " + wandered + " wandering");
    }

    /**
     * Two doors, both leading somewhere it has been. One goes back into a shop it has
     * already seen all of; the other goes to a town with more to it. The shop teaches it
     * nothing, and it went in and out of one for hours before this.
     */
    @Test
    void willNotStepBackIntoAShopItHasAlreadySeenAllOf() {
        mind.take(new Observation.MapEntered(1, 1000001, 0));   // the shop
        mind.take(new Observation.MapEntered(2, 50000, 0));     // and a town
        mind.take(new Observation.MapEntered(3, 10000, 0));     // now standing here
        mind.saw(KnownWorld.mapRef(1000001), "has_door", "out00", 4);
        mind.infer(KnownWorld.portalRef(1000001, "out00"), "leads_to",
                KnownWorld.mapRef(10000), 4);
        mind.saw(KnownWorld.mapRef(50000), "has_door", "east00", 4);
        mind.saw(KnownWorld.mapRef(50000), "has_door", "west00", 4);
        mind.infer(KnownWorld.portalRef(50000, "east00"), "leads_to",
                KnownWorld.mapRef(10000), 4);
        mind.infer(KnownWorld.portalRef(50000, "west00"), "leads_to",
                KnownWorld.mapRef(10000), 4);
        // Nothing unopened anywhere it knows of, so the frontier router finds no route and
        // the fallback rank is what actually decides. Without that the routing answers
        // first and this test proves nothing.
        mind.saw(KnownWorld.mapRef(10000), "has_door", "in00", 4);
        mind.saw(KnownWorld.mapRef(10000), "has_door", "east00", 4);
        // From here: in00 goes into the spent shop, east00 goes to the town.
        mind.infer(KnownWorld.portalRef(10000, "in00"), "leads_to",
                KnownWorld.mapRef(1000001), 4);
        mind.infer(KnownWorld.portalRef(10000, "east00"), "leads_to",
                KnownWorld.mapRef(50000), 4);

        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        // Walked in at the origin, so the shop door is the far one - which is the door the
        // "head onward rather than back" tie-break would pick on its own. Only knowing the
        // shop is spent overrides that.
        policy.cameInAt(new Point(0, 0));

        WorldModel.PortalTarget chosen = policy.pickDoor(
                List.of(new WorldModel.PortalTarget("in00", new Point(-600, 0)),
                        new WorldModel.PortalTarget("east00", new Point(40, 0))),
                mind, 10000, "player:1");

        assertEquals("east00", chosen.name(),
                "the shop has one door and it has already used it; there is nothing in there");
    }

    /**
     * Nothing left to explore, so go where you remember something living.
     *
     * Two agents inherited a map of a hundred and three places and had nowhere to go: their
     * island was fully opened by the generation before them, and the rest of that map was
     * reached by an NPC warp, which leaves no edge to walk along. Every door fell through to
     * the weakest rule available, which walked them between shops and tutorial rooms for
     * half an hour - none of which hold monsters - and a fighter did no fighting and gained
     * no levels. It was carrying three hundred sightings of monsters the whole time.
     */
    @Test
    void whenThereIsNothingLeftToOpenItGoesHunting() {
        for (int map : new int[]{10000, 40000, 50000, 60000}) {
            mind.take(new Observation.MapEntered(1, map, 0));
        }
        // Every door everywhere is opened, so there is no frontier to route to.
        record Door(int from, String name, int to) { }
        List<Door> world = List.of(
                new Door(10000, "west00", 40000), new Door(10000, "east00", 50000),
                new Door(40000, "out00", 10000), new Door(40000, "in00", 10000),
                new Door(50000, "west00", 10000), new Door(50000, "east00", 60000),
                new Door(60000, "out00", 50000), new Door(60000, "in00", 50000));
        for (Door door : world) {
            mind.saw(KnownWorld.mapRef(door.from()), "has_door", door.name(), 4);
            mind.infer(KnownWorld.portalRef(door.from(), door.name()), "leads_to",
                    KnownWorld.mapRef(door.to()), 4);
        }
        // The only thing it remembers living is two maps east.
        mind.infer("monster:100100", "present_in", KnownWorld.mapRef(60000), 4);

        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.FIGHTER);
        // Walked in at the origin, so west00 is the far door - the one the "head onward"
        // tie-break takes on its own. Only remembering the monsters points east.
        policy.cameInAt(new Point(0, 0));

        WorldModel.PortalTarget chosen = policy.pickDoor(
                List.of(new WorldModel.PortalTarget("west00", new Point(-600, 0)),
                        new WorldModel.PortalTarget("east00", new Point(40, 0))),
                mind, 10000, "player:1");

        assertEquals("east00", chosen.name(),
                "nothing left to open, so the remembered hunting ground is the reason to move");
    }

    /**
     * The one it has not met, not the one it is standing next to.
     *
     * Both agents spent half an hour in Southperry among people they had already spoken to,
     * while Shanks - who sells the only passage off Maple Island - stood two platforms above
     * them. They perceived him fourteen times and never once approached: the talk choice
     * only ever considered the nearest NPC, and one agent's highest point in the map was
     * y=140, which is exactly the height of the NPC it had already talked to.
     */
    @Test
    void crossesTheMapForSomebodyItHasNeverSpokenTo() {
        world.update(new Observation.NpcAppeared(2, 7001, 2100, new Point(20, 0)));
        world.update(new Observation.NpcAppeared(3, 7002, 22000, new Point(900, -400)));
        // It has already heard what the near one has to say, first-hand.
        mind.take(new Observation.DialogueShown(4, 2100, "hello again", 0));

        Policy talker = new ReflexPolicy(new Random(1), Disposition.TALKER);
        Intent intent = talker.decide(mind, world, 5).intent();

        Point heading = assertInstanceOf(Intent.MoveTo.class, intent).destination();
        assertEquals(new Point(900, -400), heading,
                "the stranger is nine hundred pixels away and worth the walk; the one "
                        + "underfoot has already been heard");
    }

    /** With nobody new about, the nearest is still the sensible one to bother. */
    @Test
    void otherwiseItTalksToWhoeverIsNearest() {
        world.update(new Observation.NpcAppeared(2, 7001, 2100, new Point(20, 0)));
        world.update(new Observation.NpcAppeared(3, 7002, 22000, new Point(900, -400)));

        Policy talker = new ReflexPolicy(new Random(1), Disposition.TALKER);
        Intent intent = talker.decide(mind, world, 4).intent();

        assertTrue(intent instanceof Intent.TalkTo || intent instanceof Intent.StartQuest,
                "neither has been met, so the one underfoot wins and gets talked to, got "
                        + intent.getClass().getSimpleName());
    }

    /**
     * One NPC that will not answer must not cost the agent the whole decision.
     *
     * Some NPCs never open a dialogue - the server logs "NPC 21000 is not coded" - so no
     * conversation is ever recorded and they stay strangers forever. An agent in Southperry
     * picked the nearest such stranger, got nothing, was barred from asking it again for six
     * hundred decisions, and offered no talk option at all in the meantime. Shanks stood
     * three platforms up, the only reason to be in that map, and was never considered
     * because the selection had already been spent on somebody who would not speak.
     */
    @Test
    void looksPastAnNpcItHasJustAskedAndGotNothingFrom() {
        Point nearAndSilent = new Point(20, 0);
        Point fartherAndWorthIt = new Point(900, -400);
        world.update(new Observation.NpcAppeared(2, 7001, 21000, nearAndSilent));
        world.update(new Observation.NpcAppeared(3, 7002, 22000, fartherAndWorthIt));

        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.TALKER);

        // It tries the near one first - nothing is known about either yet.
        Intent first = policy.decide(mind, world, 0).intent();
        assertTrue(first instanceof Intent.TalkTo || first instanceof Intent.MoveTo,
                "it should engage the nearest of two strangers, got "
                        + first.getClass().getSimpleName());

        // That one never answers, so no conversation is recorded. It should now look past it
        // rather than spend every decision on somebody who will not speak.
        boolean reachedForTheOtherOne = false;
        for (int decision = 1; decision < 40 && !reachedForTheOtherOne; decision++) {
            Intent intent = policy.decide(mind, world, decision).intent();
            reachedForTheOtherOne = intent instanceof Intent.MoveTo going
                    && going.destination().equals(fartherAndWorthIt);
        }

        assertTrue(reachedForTheOtherOne,
                "the silent one is asked and set aside; the other is still worth crossing to");
    }

    /**
     * Hear somebody out before taking what they are handing you.
     *
     * Shanks offers quest 1028, so an agent standing in front of him took the quest every
     * single time and never opened a conversation - and "do you want to go to Victoria
     * Island? It costs 150 mesos" only exists inside the conversation. The model could read
     * that line correctly in isolation and was never once shown it.
     */
    @Test
    void listensToSomebodyBeforeTakingTheirQuest() {
        // 2100 starts quest 1031 and this agent has never heard it speak.
        world.update(new Observation.NpcAppeared(2, 7001, 2100, new Point(20, 0)));

        Policy talker = new ReflexPolicy(new Random(1), Disposition.TALKER);
        Intent first = talker.decide(mind, world, 2).intent();

        assertInstanceOf(Intent.TalkTo.class, first,
                "it has a quest to give, but nobody has heard what else it has to say");
    }

    /** Once heard, the quest is the useful thing about them. */
    @Test
    void takesTheQuestOnceItHasHeardThemSpeak() {
        world.update(new Observation.NpcAppeared(2, 7001, 2100, new Point(20, 0)));
        mind.take(new Observation.DialogueShown(3, 2100, "good day to you", 0));

        Policy talker = new ReflexPolicy(new Random(1), Disposition.TALKER);
        Intent then = talker.decide(mind, world, 4).intent();

        assertInstanceOf(Intent.StartQuest.class, then,
                "the conversation has been had; now the quest is what is left");
    }

    /**
     * One hello is enough to have asked. Some NPCs never answer - the server calls them
     * "not coded" - so no conversation is ever recorded, and an agent that waits for one
     * before taking their quest waits for ever. One said hello three hundred and eighty-five
     * times in half an hour and did nothing else at all.
     */
    @Test
    void takesTheQuestAfterOneUnansweredHello() {
        world.update(new Observation.NpcAppeared(2, 7001, 2100, new Point(20, 0)));
        Policy talker = new ReflexPolicy(new Random(1), Disposition.TALKER);

        assertInstanceOf(Intent.TalkTo.class, talker.decide(mind, world, 2).intent(),
                "first it says hello, because nobody has heard this one speak");

        // No dialogue comes back - this one never answers anybody.
        Intent next = null;
        for (int decision = 3; decision < 12; decision++) {
            next = talker.decide(mind, world, decision).intent();
            if (next instanceof Intent.StartQuest) {
                break;
            }
        }

        assertInstanceOf(Intent.StartQuest.class, next,
                "having asked once and got nothing, it should take the quest and move on");
    }

    /**
     * A quest used to exempt an agent from ever feeling stuck, and a quest nobody was going to
     * finish held a fighter in one field for hours. Now it buys patience, and runs out.
     */
    @Test
    void aQuestDelaysFeelingStuckButDoesNotPreventIt() {
        mind.take(new Observation.MapEntered(1, 10000, 0));
        mind.saw("quest:1031", "state", "1", 1);
        ReflexPolicy reflexes = new ReflexPolicy(new Random(1), Disposition.FIGHTER);
        int patience = Disposition.FIGHTER.patience();

        for (int decision = 0; decision <= patience + 1; decision++) {
            reflexes.decide(mind, world, decision);
        }
        assertFalse(reflexes.isStale(), "one stretch of patience is not enough while a quest is open");

        for (int decision = patience + 2; decision <= 3 * patience + 2; decision++) {
            reflexes.decide(mind, world, decision);
        }
        assertTrue(reflexes.isStale(), "three stretches with nothing found is stuck, quest or not");
    }

    /** Finding something out is what resets it - not a level, not a change of map. */
    @Test
    void findingSomethingOutIsProgress() {
        mind.take(new Observation.MapEntered(1, 10000, 0));
        ReflexPolicy reflexes = new ReflexPolicy(new Random(1), Disposition.FIGHTER);
        for (int decision = 0; decision < 50; decision++) {
            reflexes.decide(mind, world, decision);
        }
        assertTrue(reflexes.decisionsSinceProgress() > 40, "" + reflexes.decisionsSinceProgress());

        mind.saw("npc:2005", "present_in", "map:10000", 60);
        reflexes.decide(mind, world, 61);

        assertEquals(0, reflexes.decisionsSinceProgress());
    }

    /**
     * Somebody who has stranded this agent is not somebody to go back to. Declining the offer
     * alone left it walking up to them, hearing it again, and saying no, on repeat.
     */
    @Test
    void doesNotGoBackToSomebodyWhoStrandedIt() {
        mind.take(new Observation.MapEntered(1, 10000, 0));
        world.update(new Observation.NpcAppeared(2, 700, 10203, new Point(30, 0)));
        mind.infer("npc:10203", "strands_you", "true", 2);
        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);

        for (int decision = 3; decision < 40; decision++) {
            Intent intent = wanderer.decide(mind, world, decision).intent();
            assertFalse(intent instanceof Intent.TalkTo talk && talk.npcId() == 10203,
                    "walked back up to the one that stranded it at decision " + decision);
            assertFalse(intent instanceof Intent.StartQuest quest && quest.npcId() == 10203);
        }
    }

    /**
     * Agent0's loop, from the trace: in Southperry the errand rule sent it to Split Road;
     * there, with the errand in the room, the stranger rule sent it back; in Southperry, with
     * the stranger in the room, the errand rule sent it again. Four hundred door decisions in
     * five minutes. Having just been somewhere for a reason is now part of what it is worth.
     */
    @Test
    void doesNotHeadStraightBackToWhereItJustWentFor() {
        int southperry = 2000000;
        int splitRoad = 1020000;
        mind.take(new Observation.MapEntered(1, splitRoad, 0));
        mind.take(new Observation.MapEntered(2, southperry, 0));
        mind.saw(KnownWorld.mapRef(southperry), "has_door", "west00", 3);
        mind.saw(KnownWorld.mapRef(splitRoad), "has_door", "east00", 3);
        mind.infer(KnownWorld.portalRef(southperry, "west00"), "leads_to", KnownWorld.mapRef(splitRoad), 3);
        mind.infer(KnownWorld.portalRef(splitRoad, "east00"), "leads_to", KnownWorld.mapRef(southperry), 3);
        // Somebody waiting in Split Road whose price it can pay, and a stranger in Southperry.
        mind.take(new Observation.StatsChanged(4, Map.of("LEVEL", 12, "MESO", 900)));
        mind.hear("npc:10200", "wants_first", "be level 10", 4);
        mind.infer("npc:10200", "present_in", KnownWorld.mapRef(splitRoad), 4);
        mind.infer("npc:2101", "present_in", KnownWorld.mapRef(southperry), 4);
        List<WorldModel.PortalTarget> southperryDoors =
                List.of(new WorldModel.PortalTarget("west00", new Point(-500, 0)));
        List<WorldModel.PortalTarget> splitRoadDoors =
                List.of(new WorldModel.PortalTarget("east00", new Point(500, 0)));

        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        world.update(new Observation.MapEntered(5, southperry, 0));
        world.update(new Observation.StatsChanged(5, Map.of("LEVEL", 12)));
        policy.decide(mind, world, 5);
        assertEquals("west00", policy.pickDoor(southperryDoors, mind, southperry, "player:1").name());
        assertEquals(Optional.of(KnownWorld.mapRef(splitRoad)), policy.destination());

        // It gets there. Whatever it did about the errand, it went for it.
        world.update(new Observation.MapEntered(6, splitRoad, 0));
        mind.take(new Observation.MapEntered(6, splitRoad, 0));
        policy.decide(mind, world, 6);
        assertEquals(Optional.empty(), policy.destination(), "arrived, so nowhere to be heading");
        assertEquals("east00", policy.pickDoor(splitRoadDoors, mind, splitRoad, "player:1").name(),
                "the stranger in Southperry is a reason to go back");

        // And back in Southperry, Split Road is somewhere it has just been for that errand.
        world.update(new Observation.MapEntered(7, southperry, 0));
        mind.take(new Observation.MapEntered(7, southperry, 0));
        policy.decide(mind, world, 7);
        policy.pickDoor(southperryDoors, mind, southperry, "player:1");
        assertFalse(policy.destination().filter(KnownWorld.mapRef(splitRoad)::equals).isPresent(),
                "heading straight back to where it has just been for the same errand");
    }

    /**
     * The model looked at every place the agent knows the way to and picked one. An unopened
     * door in this room would ordinarily come first; it is still there on the way back.
     */
    @Test
    void goesWhereTheModelChoseEvenPastAnUnopenedDoor() {
        mind.take(new Observation.MapEntered(1, 20000, 0));
        mind.take(new Observation.MapEntered(2, 10000, 0));
        mind.infer(KnownWorld.portalRef(10000, "west00"), "leads_to", KnownWorld.mapRef(20000), 3);
        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        List<WorldModel.PortalTarget> doors = List.of(
                new WorldModel.PortalTarget("west00", new Point(-500, 0)),
                new WorldModel.PortalTarget("east00", new Point(500, 0)));   // never opened

        assertEquals("east00", policy.pickDoor(doors, mind, 10000, "player:1").name(),
                "left to itself, the unopened door comes first");

        policy.headFor(KnownWorld.mapRef(20000), "somebody you have never spoken to");

        assertEquals("west00", policy.pickDoor(doors, mind, 10000, "player:1").name());
    }

    /**
     * Some NPCs have no script and never open a dialogue. They stayed strangers for ever - a
     * reason to travel to their map and to say hello on arrival - and one agent greeted three
     * of them eighty-three times between them without a word back.
     */
    @Test
    void learnsWhoNeverAnswersAndStopsGreetingThem() {
        mind.take(new Observation.MapEntered(1, 10000, 0));
        world.update(new Observation.NpcAppeared(2, 700, 12100, new Point(30, 0)));
        ReflexPolicy talker = new ReflexPolicy(new Random(1), Disposition.TALKER);

        int greetings = 0;
        int lastGreeting = -1;
        for (int decision = 3; decision < 1400; decision++) {
            Intent intent = talker.decide(mind, world, decision).intent();
            if (intent instanceof Intent.TalkTo talk && talk.npcId() == 12100) {
                greetings++;
                lastGreeting = decision;
            }
        }

        assertTrue(mind.semantic().liveBeliefs().stream()
                        .anyMatch(b -> b.subject().equals("npc:12100")
                                && b.predicate().equals("does_not_answer")
                                && b.provenance() == agents.memory.Belief.Provenance.FIRST_HAND),
                "greeted " + greetings + " times and never heard back, and did not notice");
        assertTrue(greetings <= 2, "kept greeting somebody who never answers: " + greetings);
        assertTrue(lastGreeting < 1400 - 600, "still greeting them at decision " + lastGreeting);
    }

    /** Somebody who has spoken once is not written off for one missed hello. */
    @Test
    void somebodyWhoHasAnsweredIsNeverCalledSilent() {
        mind.take(new Observation.MapEntered(1, 10000, 0));
        mind.take(new Observation.DialogueShown(2, 2100, "Welcome!", 0));
        world.update(new Observation.NpcAppeared(2, 700, 2100, new Point(30, 0)));
        ReflexPolicy talker = new ReflexPolicy(new Random(1), Disposition.TALKER);

        for (int decision = 3; decision < 1400; decision++) {
            talker.decide(mind, world, decision);
        }

        assertFalse(mind.semantic().liveBeliefs().stream()
                .anyMatch(b -> b.predicate().equals("does_not_answer")));
    }

    /**
     * Nothing to do, so it wanders - and wandering is walking somewhere, not pacing. A new
     * random point either side on every decision made an idle agent reverse direction on
     * nearly every step.
     */
    @Test
    void wandersSomewhereRatherThanPacingOnTheSpot() {
        WorldModel southperry = new WorldModel();
        southperry.update(new Observation.MapEntered(1, 2000000, 0));
        southperry.movedTo(new Point(300, 527));
        mind.take(new Observation.MapEntered(1, 2000000, 0));
        ReflexPolicy fighter = new ReflexPolicy(new Random(1), Disposition.FIGHTER);
        IntentExecutor legs = new IntentExecutor(packet -> { });

        int reversals = 0;
        int lastDirection = 0;
        for (int decision = 2; decision < 80; decision++) {
            Point before = southperry.selfPosition();
            Policy.Decision chosen = fighter.decide(mind, southperry, decision);
            if (chosen.goal().equals("wander")) {
                legs.execute(chosen.intent(), southperry);
            }
            int dx = southperry.selfPosition().x - before.x;
            if (dx != 0) {
                int direction = Integer.signum(dx);
                if (lastDirection != 0 && direction != lastDirection) {
                    reversals++;
                }
                lastDirection = direction;
            }
        }

        assertTrue(reversals <= 6, "turned round " + reversals + " times in 78 decisions of wandering");
    }

    /**
     * A Hill West of Henesys, from a ledge at y=-115. The door high up at in00 has no way to it
     * from there; the agent set off for an unreachable door, got nowhere, gave up, chose it
     * again, and paced the ledge. Only a door it can reach is a door to choose. (east00, the
     * way to Henesys, was unreachable too until the 30px step up to it counted as a jump.)
     */
    @Test
    void choosesADoorItCanActuallyGetTo() {
        int hill = 104030000;
        WorldModel ledge = new WorldModel();
        ledge.update(new Observation.MapEntered(1, hill, 0));
        ledge.movedTo(new Point(-2300, -115));
        mind.take(new Observation.MapEntered(1, hill, 0));
        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);

        java.util.Set<String> headedFor = new java.util.HashSet<>();
        for (int decision = 2; decision < 400; decision++) {
            Policy.Decision d = wanderer.decide(mind, ledge, decision);
            if (d.goal().startsWith("walk to a way out: ")) {
                headedFor.add(d.goal().substring("walk to a way out: ".length()).split(" ")[0]);
            }
        }

        assertFalse(headedFor.isEmpty(), "never set off for a door at all");
        assertFalse(headedFor.contains("in00"),
                "in00 is 1,170px up with no way to it, and it set off for it anyway: " + headedFor);
    }

    private void fullBagNextToAShopkeeper(int x) {
        mind.take(new Observation.ShopOpened(2, 1012000, List.of()));
        world.update(new Observation.InventoryShown(2, 100, Map.of(1, 24, 2, 24, 3, 24, 4, 1, 5, 24),
                List.of(new agents.percept.Item(4, 1, 4000019, 1, null))));
        world.update(new Observation.NpcAppeared(3, 7001, 1012000, new Point(x, 0)));
    }

    /** With a full bag, somebody who has opened a shop before is worth the walk. */
    @Test
    void goesToSellWhenTheBagIsFull() {
        fullBagNextToAShopkeeper(20);

        Intent intent = policy.decide(mind, world, 4).intent();

        assertEquals(1012000, assertInstanceOf(Intent.TalkTo.class, intent).npcId());
    }

    @Test
    void walksOverToAShopkeeperOutOfReach() {
        fullBagNextToAShopkeeper(300);

        Intent intent = policy.decide(mind, world, 4).intent();

        assertEquals(new Point(300, 0), assertInstanceOf(Intent.MoveTo.class, intent).destination());
    }

    @Test
    void hasNoBusinessWithAShopWithRoomInTheBag() {
        mind.take(new Observation.ShopOpened(2, 1012000, List.of()));
        world.update(new Observation.InventoryShown(2, 0, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24), List.of()));

        assertFalse(ReflexPolicy.needsAShop(world, mind, 4));
    }

    /** Knowing what heals and carrying none of it, with money, is business too. */
    @Test
    void goesShoppingWhenOutOfWhatHeals() {
        mind.take(new Observation.ShopOpened(2, 1012000, List.of(
                new Observation.ShopOpened.ShopItem(0, 2000000, 50))));
        mind.infer("item:2000000", "restores_hp", "true", 2);
        world.update(new Observation.InventoryShown(2, 300, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24), List.of()));
        world.update(new Observation.NpcAppeared(3, 7001, 1012000, new Point(20, 0)));

        assertTrue(ReflexPolicy.needsAShop(world, mind, 4));
        assertEquals(1012000, assertInstanceOf(Intent.TalkTo.class, policy.decide(mind, world, 4).intent()).npcId());
    }

    /**
     * The loop this was written for: an agent out of potions walked to the pet-food seller
     * forty times an hour, because it ran a shop, for potions it had never stocked.
     */
    @Test
    void doesNotGoForPotionsToAShopThatHasNeverStockedThem() {
        mind.take(new Observation.ShopOpened(2, 1012004, List.of(
                new Observation.ShopOpened.ShopItem(0, 2120000, 30))));
        mind.infer("item:2000000", "restores_hp", "true", 2);
        world.update(new Observation.InventoryShown(2, 300, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24), List.of()));
        world.update(new Observation.NpcAppeared(3, 7001, 1012004, new Point(20, 0)));

        assertFalse(ReflexPolicy.needsAShop(world, mind, 4));
        assertFalse(policy.decide(mind, world, 4).goal().contains("heals"));
    }

    /** With a full bag any shop will do: they all buy. */
    @Test
    void sellsToAnyShopWhenTheBagIsFull() {
        mind.take(new Observation.ShopOpened(2, 1012004, List.of(
                new Observation.ShopOpened.ShopItem(0, 2120000, 30))));
        world.update(new Observation.InventoryShown(2, 100, Map.of(1, 24, 2, 24, 3, 24, 4, 1, 5, 24),
                List.of(new agents.percept.Item(4, 1, 4000019, 1, null))));
        world.update(new Observation.NpcAppeared(3, 7001, 1012004, new Point(20, 0)));

        assertEquals(1012004, assertInstanceOf(Intent.TalkTo.class, policy.decide(mind, world, 4).intent()).npcId());
    }

    /** A line from 10000: west00 to 20000, east00 to 30000 with a door never opened beyond. */
    private void aJunction() {
        mind.take(new Observation.MapEntered(1, 20000, 0));
        mind.take(new Observation.MapEntered(2, 30000, 0));
        mind.take(new Observation.MapEntered(3, 10000, 0));
        mind.infer(KnownWorld.portalRef(10000, "west00"), "leads_to", KnownWorld.mapRef(20000), 4);
        mind.infer(KnownWorld.portalRef(10000, "east00"), "leads_to", KnownWorld.mapRef(30000), 4);
        mind.saw(KnownWorld.mapRef(30000), "has_door", "north00", 4);
    }

    private static final List<WorldModel.PortalTarget> JUNCTION_DOORS = List.of(
            new WorldModel.PortalTarget("west00", new Point(-500, 0)),
            new WorldModel.PortalTarget("east00", new Point(500, 0)));

    /** "Get this letter to #p1072000# who's around #m102020300#": being sent is a reason to go. */
    @Test
    void goesWhereItWasSent() {
        aJunction();
        mind.hear("npc:1022000", "sends_you_to", "map:20000", 5);
        world.update(new Observation.MapEntered(6, 10000, 0));
        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        policy.decide(mind, world, 6);
        policy.cameInAt(new Point(-500, 0));

        assertEquals("west00", policy.pickDoor(JUNCTION_DOORS, mind, 10000, "player:1").name(),
                "the unopened door east is the better trip, and it should lose to being sent west");
    }

    /** Once it has been there since being told, the instruction is spent. */
    @Test
    void anInstructionIsSpentOnceCarriedOut() {
        aJunction();
        mind.hear("npc:1022000", "sends_you_to", "map:20000", 5);
        mind.take(new Observation.MapEntered(6, 20000, 0));
        mind.take(new Observation.MapEntered(7, 10000, 0));
        world.update(new Observation.MapEntered(7, 10000, 0));
        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        policy.decide(mind, world, 8);
        policy.cameInAt(new Point(-500, 0));

        assertEquals("east00", policy.pickDoor(JUNCTION_DOORS, mind, 10000, "player:1").name());
    }

    /**
     * A first-job warrior at 30 goes back to whoever trained it - which is how the second job
     * begins - and not again until it has something new to show them.
     */
    @Test
    void goesBackToItsTrainerWhenItHasGrown() {
        aJunction();
        // Trained in its own room at 20000, then walked back to the junction.
        mind.take(new Observation.MapEntered(4, 20000, 0));
        mind.take(new Observation.DialogueShown(4, 1022000, "Go, young Warrior!", 0));
        mind.saw("self", "trained_by", "npc:1022000", 4);
        mind.take(new Observation.MapEntered(5, 10000, 0));
        mind.take(new Observation.StatsChanged(9, Map.of("LEVEL", 30, "JOB", 100)));
        world.update(new Observation.MapEntered(10, 10000, 0));
        world.update(new Observation.StatsChanged(10, Map.of("LEVEL", 30, "JOB", 100)));
        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        policy.decide(mind, world, 10);
        policy.cameInAt(new Point(-500, 0));

        assertEquals("west00", policy.pickDoor(JUNCTION_DOORS, mind, 10000, "player:1").name(),
                "level 30 since they last spoke: back to the one who trained it");
    }

    @Test
    void thirtyOfWhatWasAskedForIsWhatWasAskedFor() {
        java.util.function.IntUnaryOperator twelveMarbles = id -> id == 4031013 ? 12 : 0;
        java.util.function.IntUnaryOperator thirtyMarbles = id -> id == 4031013 ? 30 : 0;

        assertFalse(ReflexPolicy.canPay("30 item:4031013", 30, 0, twelveMarbles));
        assertTrue(ReflexPolicy.canPay("30 item:4031013", 30, 0, thirtyMarbles));
    }

    /** Having shown them already, there is nothing to go back for until something else changes. */
    @Test
    void doesNotKeepGoingBackToATrainerItHasAlreadyShown() {
        aJunction();
        mind.take(new Observation.MapEntered(4, 20000, 0));
        mind.take(new Observation.DialogueShown(4, 1022000, "Go, young Warrior!", 0));
        mind.saw("self", "trained_by", "npc:1022000", 4);
        mind.take(new Observation.StatsChanged(9, Map.of("LEVEL", 30, "JOB", 100)));
        mind.take(new Observation.DialogueShown(10, 1022000, "The progress you have made is astonishing.", 0));
        mind.take(new Observation.MapEntered(11, 10000, 0));
        world.update(new Observation.MapEntered(11, 10000, 0));
        world.update(new Observation.StatsChanged(11, Map.of("LEVEL", 30, "JOB", 100)));
        ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        policy.decide(mind, world, 12);
        policy.cameInAt(new Point(-500, 0));

        assertEquals("east00", policy.pickDoor(JUNCTION_DOORS, mind, 10000, "player:1").name(),
                "it has spoken to its trainer since reaching 30, so the unopened door wins");
    }

    private WorldModel hurt(int hp, int maxHp, boolean potions) {
        world.update(new Observation.StatsChanged(2, Map.of("HP", hp, "MAXHP", maxHp)));
        world.update(new Observation.InventoryShown(2, 0, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24),
                potions ? List.of(new agents.percept.Item(2, 1, 2000000, 5, null)) : List.of()));
        world.update(new Observation.MonsterAppeared(3, 9001, 100100, new Point(20, 0)));
        return world;
    }

    /** Six deaths in two hours: at a fifth of its health, with nothing to drink, it went on fighting. */
    @Test
    void backsAwayWhenHurtWithNothingToDrink() {
        hurt(30, 150, false);
        ReflexPolicy fighter = new ReflexPolicy(new Random(1), Disposition.FIGHTER);

        Policy.Decision decision = fighter.decide(mind, world, 4);

        assertFalse(decision.intent() instanceof Intent.Attack, "hit a monster at 30/150 with nothing to drink");
        Point to = assertInstanceOf(Intent.MoveTo.class, decision.intent()).destination();
        assertTrue(to.x < 0, "backed towards the monster rather than away: " + to);
    }

    /** With a potion in the bag and health not yet dire, drinking is Survival's job and fighting goes on. */
    @Test
    void keepsFightingWhenItHasSomethingToDrink() {
        hurt(45, 150, true);
        ReflexPolicy fighter = new ReflexPolicy(new Random(1), Disposition.FIGHTER);

        assertInstanceOf(Intent.Attack.class, fighter.decide(mind, world, 4).intent());
    }

    @Test
    void backsOffWhenBadlyHurtEvenWithSomethingToDrink() {
        hurt(20, 150, true);
        ReflexPolicy fighter = new ReflexPolicy(new Random(1), Disposition.FIGHTER);

        assertFalse(fighter.decide(mind, world, 4).intent() instanceof Intent.Attack);
    }

    /** Revived at a sixth of its health, it walked straight back into the tunnel it died in. */
    @Test
    void restsBeforeSettingOffWhenHurtWithNothingToDrink() {
        aJunction();
        world.update(new Observation.MapEntered(5, 10000, 0));
        world.update(new Observation.StatsChanged(5, Map.of("HP", 50, "MAXHP", 295)));
        world.update(new Observation.InventoryShown(5, 0, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24), List.of()));
        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        wanderer.headFor(KnownWorld.mapRef(30000), "somewhere the model chose");

        for (int decision = 6; decision < 40; decision++) {
            Policy.Decision d = wanderer.decide(mind, world, decision);
            assertFalse(d.goal().startsWith("walk to a way out") || d.intent() instanceof Intent.EnterPortal,
                    "set off at 50/295 with nothing to drink: " + d.goal());
        }
    }

    /** The plan that killed it is not the plan to resume. */
    @Test
    void forgetsWhereItWasGoingWhenItDies() {
        aJunction();
        world.update(new Observation.MapEntered(5, 10000, 0));
        world.update(new Observation.StatsChanged(5, Map.of("LEVEL", 18, "HP", 290, "MAXHP", 295)));
        mind.take(new Observation.StatsChanged(5, Map.of("LEVEL", 18)));
        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        wanderer.decide(mind, world, 6);
        wanderer.headFor(KnownWorld.mapRef(30000), "somewhere the model chose");

        mind.saw("map:105050000", "killed_you_at_level", "18", 7);
        wanderer.decide(mind, world, 8);

        assertEquals(Optional.empty(), wanderer.destination());
    }

    /** With nothing better to do, the last resort used to be any door that works - including into the tunnel. */
    @Test
    void neverTakesADoorIntoSomewhereThatKeepsKillingIt() {
        aJunction();
        mind.saw(KnownWorld.mapRef(30000), "has_door", "south00", 4);
        mind.infer(KnownWorld.portalRef(30000, "north00"), "leads_to", KnownWorld.mapRef(10000), 4);
        mind.infer(KnownWorld.portalRef(30000, "south00"), "leads_to", KnownWorld.mapRef(10000), 4);
        mind.take(new Observation.StatsChanged(4, Map.of("LEVEL", 18)));
        mind.saw(KnownWorld.mapRef(30000), "killed_you_at_level", "18", 4);
        mind.take(new Observation.MapEntered(5, 10000, 0));     // a second death, another moment
        mind.saw(KnownWorld.mapRef(30000), "killed_you_at_level", "18", 5);
        world.update(new Observation.MapEntered(6, 10000, 0));
        world.update(new Observation.StatsChanged(6, Map.of("LEVEL", 18, "HP", 290, "MAXHP", 295)));
        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        wanderer.decide(mind, world, 7);
        wanderer.cameInAt(new Point(-500, 0));

        assertEquals("west00", wanderer.pickDoor(JUNCTION_DOORS, mind, 10000, "player:1").name(),
                "east00 leads into the map that killed it twice");
    }

    /**
     * A pet-food shop has no potions. Agent2 walked back to one for them about forty times an
     * hour, came away with the same 392 mesos every time, and went back.
     */
    @Test
    void doesNotGoBackToAShopThatWasNoUseWhileNothingHasChanged() {
        fullBagNextToAShopkeeper(20);
        mind.saw("npc:1012000", "shop_was_no_use", "100", 3);

        String goal = policy.decide(mind, world, 4).goal();

        assertFalse(goal.contains("sell what I cannot carry"),
                "went straight back to a shop that did nothing for it, with the same 100 mesos: " + goal);
    }

    /** With different money it might be of use again, so it is worth another look. */
    @Test
    void triesTheShopAgainOnceItsMoneyHasChanged() {
        fullBagNextToAShopkeeper(20);
        mind.saw("npc:1012000", "shop_was_no_use", "40", 3);

        Intent intent = policy.decide(mind, world, 4).intent();

        assertEquals(1012000, assertInstanceOf(Intent.TalkTo.class, intent).npcId());
    }

    /** Nothing worth going to, so any door will do - but not the one it just came through. */
    @Test
    void theLastResortDoesNotGoStraightBack() {
        // Made-up maps with nothing in Map.wz, so no doors are seen but the ones given here.
        int hub = 990000001, before = 990000002, other = 990000003;
        mind.take(new Observation.MapEntered(1, before, 0));
        mind.take(new Observation.MapEntered(2, other, 0));
        mind.take(new Observation.MapEntered(3, hub, 0));
        for (String door : new String[]{"west00", "east00"}) {
            mind.saw(KnownWorld.mapRef(hub), "has_door", door, 4);
        }
        mind.infer(KnownWorld.portalRef(hub, "west00"), "leads_to", KnownWorld.mapRef(before), 4);
        mind.infer(KnownWorld.portalRef(hub, "east00"), "leads_to", KnownWorld.mapRef(other), 4);
        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        world.update(new Observation.MapEntered(5, before, 0));
        wanderer.decide(mind, world, 5);
        world.update(new Observation.MapEntered(6, hub, 0));       // came in from `before`
        wanderer.decide(mind, world, 6);
        wanderer.cameInAt(new Point(500, 0));                       // beside east00, so "onward" alone says west

        assertEquals("east00", wanderer.pickDoor(JUNCTION_DOORS, mind, hub, "player:1").name(),
                "west00 goes straight back to where it just came from");
    }

    /**
     * The new generation's first ten minutes: three agents crossing between Southperry and
     * Split Road every second and a half, each map's best door pointing at the other. Once it
     * is going back and forth, the way back is not taken while there is another.
     */
    @Test
    void stopsGoingBackAndForthBetweenTwoMaps() {
        int hub = 990000001, before = 990000002, other = 990000003;
        mind.take(new Observation.MapEntered(1, other, 0));
        for (String door : new String[]{"west00", "east00"}) {
            mind.saw(KnownWorld.mapRef(hub), "has_door", door, 2);
        }
        mind.infer(KnownWorld.portalRef(hub, "west00"), "leads_to", KnownWorld.mapRef(before), 2);
        mind.infer(KnownWorld.portalRef(hub, "east00"), "leads_to", KnownWorld.mapRef(other), 2);
        mind.saw(KnownWorld.mapRef(before), "has_door", "north00", 2);    // a reason to go back west
        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);
        int tick = 3;
        for (int map : new int[]{hub, before, hub, before, hub}) {
            world.update(new Observation.MapEntered(tick, map, 0));
            mind.take(new Observation.MapEntered(tick, map, 0));
            wanderer.decide(mind, world, tick++);
        }
        assertTrue(wanderer.bouncing());
        wanderer.cameInAt(new Point(500, 0));

        assertEquals("east00", wanderer.pickDoor(JUNCTION_DOORS, mind, hub, "player:1").name(),
                "the unopened door beyond west00 would draw it back to the map it keeps bouncing to");
    }

    /** Silence found once, in a conversation that had jammed, should not be believed for ever. */
    @Test
    void findingSomebodySilentWearsOff() {
        mind.take(new Observation.MapEntered(1, 10000, 0));
        mind.saw("npc:1061100", "does_not_answer", "true", 1);
        assertTrue(ReflexPolicy.doesNotAnswer(mind).contains("npc:1061100"));

        mind.take(new Observation.MapEntered(1 + ReflexPolicy.SILENCE_LASTS, 10000, 0));

        assertFalse(ReflexPolicy.doesNotAnswer(mind).contains("npc:1061100"),
                "an hour on, it is worth saying hello again");
    }

    /**
     * Agent0, a level-33 thief in its trainer's room, started quests with the Dark Lord again
     * and again and never once opened a conversation, which is where the second job is offered.
     */
    @Test
    void talksToItsTrainerRatherThanTakingTheirQuest() {
        mind.take(new Observation.MapEntered(1, 103000003, 0));
        mind.take(new Observation.DialogueShown(2, 1052001, "Go now, and live as a proud Thief.", 0));
        mind.saw("self", "trained_by", "npc:1052001", 2);
        mind.take(new Observation.StatsChanged(5, Map.of("LEVEL", 33, "JOB", 400)));
        world.update(new Observation.MapEntered(6, 103000003, 0));
        world.update(new Observation.StatsChanged(6, Map.of("LEVEL", 33, "JOB", 400)));
        world.movedTo(new Point(0, 128));
        world.update(new Observation.NpcAppeared(6, 5000, 1052001, new Point(20, 128)));
        ReflexPolicy wanderer = new ReflexPolicy(new Random(1), Disposition.WANDERER);

        Policy.Decision decision = wanderer.decide(mind, world, 7);

        assertInstanceOf(Intent.TalkTo.class, decision.intent(),
                "did not go to hear what comes next: " + decision.goal() + " " + decision.considered());
        assertEquals(1052001, ((Intent.TalkTo) decision.intent()).npcId());
    }
}
