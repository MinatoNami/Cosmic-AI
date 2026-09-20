package agents.mind;

import agents.Mind;
import agents.percept.Observation;
import agents.trace.Trace;
import agents.world.WorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        Point destination = assertInstanceOf(Intent.MoveTo.class, intent).destination();
        assertEquals(0, destination.y);
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
        assertTrue(decision.considered().contains("Attack"));
    }

    /**
     * The starvation this exists to prevent: loot and monsters sit above NPCs, quests and
     * doors in the ladder and feed each other - killing a monster makes a drop, and a drop
     * outranks a monster - so ninety seconds of a real agent came to eighty-eight per cent
     * fighting and looting, and it never spoke to anybody.
     */
    @Test
    void looksUpFromFightingOftenEnoughToDoSomethingElse() {
        // A monster permanently within reach, which is the situation that starves everything
        // below it: hit it, loot it, and there is always another.
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(20, 0)));
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
        }

        assertTrue(grinding > 0, "it should still be fighting most of the time");
        assertTrue(lookedUp > 0,
                "it never looked up once in " + (Disposition.FIGHTER.attentionSpan() * 3)
                        + " decisions, so nothing below the monsters can ever happen");
    }
}
