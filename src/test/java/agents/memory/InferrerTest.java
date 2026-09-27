package agents.memory;

import agents.percept.Observation;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first thing an agent is allowed to work out rather than be told.
 */
class InferrerTest {

    private final Inferrer inferrer = new Inferrer();

    /** A monster appears, moves nowhere, dies, and something falls where it stood. */
    private List<Inferrer.Conclusion> killOne(int objectId, int monsterId, int itemId, long at) {
        inferrer.consider(new Observation.MonsterAppeared(at, objectId, monsterId, new Point(100, 0)));
        inferrer.consider(new Observation.MonsterDied(at + 1, objectId));
        return inferrer.consider(new Observation.DropAppeared(
                at + 2, 7000 + objectId, itemId, false, new Point(105, 0)));
    }

    @Test
    void concludesWhatAMonsterDropsAfterSeeingItTwice() {
        assertTrue(killOne(1, 100100, 2000000, 10).isEmpty(),
                "once is a coincidence and should not become a belief");

        List<Inferrer.Conclusion> second = killOne(2, 100100, 2000000, 30);

        assertEquals(List.of(new Inferrer.Conclusion("monster:100100", "drops", "item:2000000")),
                second);
    }

    @Test
    void saysItOnlyOnce() {
        killOne(1, 100100, 2000000, 10);
        killOne(2, 100100, 2000000, 30);

        assertTrue(killOne(3, 100100, 2000000, 50).isEmpty(),
                "it already believes this; repeating it is corroboration, not a conclusion");
    }

    /** A kill followed at once by experience, twice over, is what that monster is worth. */
    @Test
    void concludesWhatAKillIsWorth() {
        for (int i = 1; i <= 2; i++) {
            long at = i * 20L;
            inferrer.consider(new Observation.MonsterAppeared(at, i, 100100, new Point(100, 0)));
            inferrer.consider(new Observation.MonsterDied(at + 1, i));
            List<Inferrer.Conclusion> said = inferrer.consider(new Observation.ExpGained(at + 2, 3));
            if (i == 2) {
                assertEquals(List.of(new Inferrer.Conclusion("monster:100100", "gives_exp", "3")), said);
            } else {
                assertTrue(said.isEmpty());
            }
        }
    }

    @Test
    void experienceWithNoDeathNearbyIsCreditedToNothing() {
        assertTrue(inferrer.consider(new Observation.ExpGained(5, 3)).isEmpty());
        assertTrue(inferrer.consider(new Observation.ExpGained(6, 3)).isEmpty());
    }

    /** Going out of sight is not dying, so whatever falls next is not what it dropped. */
    @Test
    void creditsNothingToAMonsterThatOnlyVanished() {
        for (int i = 1; i <= 3; i++) {
            long at = i * 20L;
            inferrer.consider(new Observation.MonsterAppeared(at, i, 100100, new Point(100, 0)));
            inferrer.consider(new Observation.MonsterVanished(at + 1, i));
            assertTrue(inferrer.consider(new Observation.DropAppeared(
                    at + 2, 7000 + i, 2000000, false, new Point(105, 0))).isEmpty());
        }
    }

    /** Something falling long after a death came from something else. */
    @Test
    void doesNotCreditADeathTooLongAgo() {
        inferrer.consider(new Observation.MonsterAppeared(10, 1, 100100, new Point(100, 0)));
        inferrer.consider(new Observation.MonsterDied(11, 1));
        inferrer.consider(new Observation.DropAppeared(200, 7001, 2000000, false, new Point(105, 0)));

        inferrer.consider(new Observation.MonsterAppeared(210, 2, 100100, new Point(100, 0)));
        inferrer.consider(new Observation.MonsterDied(211, 2));

        assertTrue(inferrer.consider(new Observation.DropAppeared(
                400, 7002, 2000000, false, new Point(105, 0))).isEmpty());
    }

    /** And something falling across the map came from something else too. */
    @Test
    void doesNotCreditADeathTooFarAway() {
        inferrer.consider(new Observation.MonsterAppeared(10, 1, 100100, new Point(100, 0)));
        inferrer.consider(new Observation.MonsterDied(11, 1));
        inferrer.consider(new Observation.DropAppeared(12, 7001, 2000000, false, new Point(9000, 0)));

        inferrer.consider(new Observation.MonsterAppeared(20, 2, 100100, new Point(100, 0)));
        inferrer.consider(new Observation.MonsterDied(21, 2));

        assertTrue(inferrer.consider(new Observation.DropAppeared(
                22, 7002, 2000000, false, new Point(9000, 0))).isEmpty());
    }

    /** Meso is not an item id, and the agent should not invent one. */
    @Test
    void namesMesoAsMeso() {
        killOne(1, 100100, 0, 10);
        inferrer.consider(new Observation.MonsterAppeared(30, 2, 100100, new Point(100, 0)));
        inferrer.consider(new Observation.MonsterDied(31, 2));

        List<Inferrer.Conclusion> concluded = inferrer.consider(
                new Observation.DropAppeared(32, 7002, 0, true, new Point(105, 0)));

        assertTrue(concluded.isEmpty() || concluded.get(0).object().equals("meso"),
                "meso should be called meso: " + concluded);
    }
}
