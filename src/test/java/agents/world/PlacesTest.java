package agents.world;

import agents.memory.Belief;
import agents.memory.SemanticMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoring places against each other rather than asking one question at a time.
 *
 * The world here is a line: map:1 - map:2 - map:3, doors both ways.
 */
class PlacesTest {

    private static final Places.Weights WEIGHTS =
            new Places.Weights(2.0, 1.5, 0.8, 3.0, 0.6, 1.5, 0.3, 0.35, 1.0, 1.0);

    private SemanticMemory memory;
    private final Map<String, Integer> visits = new HashMap<>();

    @BeforeEach
    void setUp() {
        memory = new SemanticMemory();
        door(1, "east00", 2);
        door(2, "west00", 1);
        door(2, "east00", 3);
        door(3, "west00", 2);
        visits.put("map:1", 3);
        visits.put("map:2", 3);
        visits.put("map:3", 3);
    }

    private void door(int from, String name, int to) {
        believe("map:" + from, "has_door", name);
        believe(KnownWorld.portalRef(from, name), "leads_to", "map:" + to);
    }

    private void believe(String s, String p, String o) {
        memory.assertTriple(s, p, o, 0, 0, Belief.Provenance.FIRST_HAND);
    }

    private List<Places.Place> rank(String errandMap, Set<String> avoid, Map<String, Double> justBeen) {
        return Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, errandMap, Set.of(), avoid, justBeen), WEIGHTS);
    }

    @Test
    void somewhereWithNothingToFindIsNotOffered() {
        assertTrue(rank(null, Set.of(), Map.of()).isEmpty());
    }

    @Test
    void aReasonFartherAwayCanStillWinIfItIsWorthMore() {
        believe("npc:5", "present_in", "map:2");                 // a stranger next door
        believe("map:3", "has_door", "north00");                 // an unopened door two away

        List<Places.Place> places = rank(null, Set.of(), Map.of());

        assertEquals("map:3", places.get(0).map(), "an unopened door outweighs a stranger, walk and all");
        assertEquals("east00", places.get(0).firstDoor());
        assertEquals(2, places.get(0).hops());
        assertEquals("map:2", places.get(1).map());
    }

    @Test
    void anErrandOutweighsCuriosity() {
        believe("map:3", "has_door", "north00");
        believe("npc:5", "present_in", "map:2");

        assertEquals("map:2", rank("map:2", Set.of(), Map.of()).get(0).map());
    }

    /** The loop this was written for: a trip just made is not the next one to make. */
    @Test
    void somewhereJustBeenForDropsOutUntilItWearsOff() {
        assertEquals("map:2", rank("map:2", Set.of(), Map.of()).get(0).map());
        assertTrue(rank("map:2", Set.of(), Map.of("map:2", 1.0)).isEmpty(),
                "just came back from there, errand and all");
        assertFalse(rank("map:2", Set.of(), Map.of("map:2", 0.1)).isEmpty(),
                "long enough ago and it is worth going again");
    }

    @Test
    void somebodyItWillNotDealWithIsNoReasonToGo() {
        believe("npc:10203", "present_in", "map:2");

        assertTrue(rank(null, Set.of("npc:10203"), Map.of()).isEmpty());
    }

    @Test
    void theLeastVisitedWinsBetweenEqualReasons() {
        believe("npc:5", "present_in", "map:2");
        believe("npc:6", "present_in", "map:3");
        visits.put("map:2", 40);
        visits.put("map:3", 0);         // and never having been there is a reason of its own

        assertEquals("map:3", rank(null, Set.of(), Map.of()).get(0).map());
    }

    @Test
    void saysWhyInIds() {
        believe("npc:5", "present_in", "map:2");

        String line = rank(null, Set.of(), Map.of()).get(0).describe();

        assertEquals("map:2, 1 door away: somebody you have never spoken to, been 3 times", line);
    }
}
