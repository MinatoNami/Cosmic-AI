package agents.mind;

import agents.Mind;
import agents.percept.Item;
import agents.percept.Observation;
import agents.trace.Trace;
import agents.world.WorldModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalTest {

    private static final int RED_POTION = 2000000;
    private static final int BLUE_POTION = 2000003;
    private static final int RETURN_SCROLL = 2030000;

    @TempDir
    Path traceDir;

    private Mind mind;
    private final WorldModel world = new WorldModel((map, portal) -> Optional.empty());
    private final Survival survival = new Survival();
    private long tick = 1;

    @BeforeEach
    void wake() {
        mind = new Mind("Test", Trace.toFile(traceDir.resolve("t.jsonl"), "Test"));
        see(new Observation.NoticeShown(tick, "hello"));    // something to hang beliefs on
    }

    @AfterEach
    void sleep() {
        mind.close();
    }

    private void see(Observation observation) {
        world.update(observation);
        mind.take(observation);
    }

    private void carrying(Item... items) {
        see(new Observation.InventoryShown(++tick, 0, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24), List.of(items)));
    }

    private void health(int hp, int maxHp) {
        see(new Observation.StatsChanged(++tick, Map.of("HP", hp, "MAXHP", hp > maxHp ? hp : maxHp,
                "MP", 20, "MAXMP", 20)));
    }

    private Optional<Integer> step() {
        return survival.step(mind, world, ++tick);
    }

    private String belief(String subject, String predicate) {
        return mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals(subject) && b.predicate().equals(predicate))
                .map(b -> b.object())
                .findFirst().orElse(null);
    }

    @Test
    void drinksNothingWhileHealthy() {
        carrying(new Item(2, 1, RED_POTION, 5, null));
        health(45, 50);

        assertTrue(step().isEmpty());
    }

    /**
     * Nothing says a red potion heals. Hurt and knowing nothing, it tries one, and what its
     * health does next is what it believes.
     */
    @Test
    void learnsWhatHealsByDrinkingIt() {
        carrying(new Item(2, 1, RED_POTION, 5, null));
        health(20, 50);

        assertEquals(Optional.of(RED_POTION), step());

        health(70, 50);
        step();
        assertEquals("true", belief("item:" + RED_POTION, "restores_hp"));
    }

    @Test
    void reachesForWhatItKnowsHealsOnceItKnows() {
        carrying(new Item(2, 1, BLUE_POTION, 5, null), new Item(2, 2, RED_POTION, 5, null));
        mind.infer("item:" + RED_POTION, "restores_hp", "true", tick);
        health(20, 50);

        assertEquals(Optional.of(RED_POTION), step());
    }

    /** Something that did nothing for its health is not tried for health again. */
    @Test
    void remembersWhatDidNothing() {
        carrying(new Item(2, 1, BLUE_POTION, 5, null));
        health(20, 50);
        assertEquals(Optional.of(BLUE_POTION), step());

        for (int i = 0; i < Survival.WATCH_STEPS + Survival.BETWEEN_DRINKS; i++) {
            step();
        }

        assertEquals("false", belief("item:" + BLUE_POTION, "restores_hp"));
        assertTrue(step().isEmpty(), "nothing left worth trying");
    }

    /** A scroll that warps you home is not something to find out about by drinking it. */
    @Test
    void neverExperimentsWithScrolls() {
        carrying(new Item(2, 1, RETURN_SCROLL, 5, null));
        health(5, 50);

        assertTrue(step().isEmpty());
    }

    @Test
    void doesNotDrinkFasterThanTheClientWouldLet() {
        carrying(new Item(2, 1, RED_POTION, 50, null));
        mind.infer("item:" + RED_POTION, "restores_hp", "true", tick);
        health(10, 50);

        assertTrue(step().isPresent());
        assertTrue(step().isEmpty());
        assertTrue(step().isEmpty());
        assertTrue(step().isPresent());
    }

    @Test
    void theDeadDrinkNothing() {
        carrying(new Item(2, 1, RED_POTION, 5, null));
        health(0, 50);

        assertTrue(step().isEmpty());
    }
}
