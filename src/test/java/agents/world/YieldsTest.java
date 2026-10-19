package agents.world;

import agents.memory.Belief;
import agents.memory.SemanticMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a hunting ground pays a minute: measured where it has been, estimated where not. */
class YieldsTest {

    private SemanticMemory memory;
    private long episode;

    @BeforeEach
    void setUp() {
        memory = new SemanticMemory();
    }

    private void believe(String s, String p, String o, int times) {
        for (int i = 0; i < times; i++) {
            memory.assertTriple(s, p, o, ++episode, episode, Belief.Provenance.FIRST_HAND);
        }
    }

    /**
     * Agent0 held 27 amounts for the Evil Eye, from 8 to 1500. The one seen most often is what
     * it gives; the largest is a kill credited to the wrong death.
     */
    @Test
    void aMonsterIsWorthWhatItGaveMostOften() {
        believe("monster:2230100", "gives_exp", "70", 5);
        believe("monster:2230100", "gives_exp", "1500", 1);
        believe("monster:2230100", "gives_exp", "8", 1);

        assertEquals(70.0, Yields.perKill(memory.liveBeliefs()).get("monster:2230100"));
    }

    @Test
    void whatWasEarnedThereBeatsWhatItsMonstersSuggest() {
        believe("monster:1", "present_in", "map:100", 1);
        believe("monster:1", "gives_exp", "1000", 3);
        believe("map:100", Yields.EARNS_PER_MINUTE, Yields.measurement(45, 300), 1);

        assertEquals(300.0, Yields.expected(memory.liveBeliefs(), 45).get("map:100"));
    }

    /**
     * A place never hunted is estimated at what its monsters give a kill, at the pace the agent
     * kills where it has measured both: 150 a kill making 300 a minute is two kills a minute.
     */
    @Test
    void anUnhuntedPlaceIsEstimatedAtTheAgentsOwnPace() {
        believe("monster:pig", "present_in", "map:field", 1);
        believe("monster:pig", "gives_exp", "150", 3);
        believe("map:field", Yields.EARNS_PER_MINUTE, Yields.measurement(45, 300), 1);
        believe("monster:lupin", "present_in", "map:forest", 1);
        believe("monster:lupin", "gives_exp", "400", 3);

        double forest = Yields.expected(memory.liveBeliefs(), 45).get("map:forest");

        assertEquals(400 * 2 * Yields.ESTIMATE_TRUST, forest, 0.01);
    }

    @Test
    void whatAPlacePaidLevelsAgoSaysNothingNow() {
        believe("map:field", Yields.EARNS_PER_MINUTE, Yields.measurement(20, 400), 1);

        assertFalse(Yields.measured(memory.liveBeliefs(), 45).containsKey("map:field"));
        assertTrue(Yields.measured(memory.liveBeliefs(), 21).containsKey("map:field"));
    }

    @Test
    void severalStaysAtAboutThisLevelAreAveraged() {
        believe("map:field", Yields.EARNS_PER_MINUTE, Yields.measurement(44, 200), 1);
        believe("map:field", Yields.EARNS_PER_MINUTE, Yields.measurement(45, 400), 1);

        assertEquals(Map.of("map:field", 300.0), Yields.measured(memory.liveBeliefs(), 45));
    }

    /** Born knowing what drakes give, at level one with nothing measured: nothing is estimated. */
    @Test
    void nothingIsEstimatedBeforeAnythingIsMeasuredAtThisLevel() {
        believe("monster:drake", "present_in", "map:cave", 1);
        believe("monster:drake", "gives_exp", "1350", 3);

        assertTrue(Yields.expected(memory.liveBeliefs(), 1).isEmpty());
    }

    @Test
    void anEstimateNeverPromisesMoreThanAFewTimesTheBestMeasured() {
        believe("map:field", Yields.EARNS_PER_MINUTE, Yields.measurement(45, 100), 1);
        believe("monster:drake", "present_in", "map:cave", 1);
        believe("monster:drake", "gives_exp", "1350", 3);

        assertEquals(100 * Yields.ESTIMATE_CEILING, Yields.expected(memory.liveBeliefs(), 45).get("map:cave"), 0.01);
    }
}
