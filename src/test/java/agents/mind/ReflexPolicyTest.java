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
import java.util.List;
import java.util.Map;
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
}
