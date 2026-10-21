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
            new Places.Weights(2.0, 1.5, 0.8, 3.0, 3.5, 0.6, 1.5, 0.3, 0.35, 1.0, 1.0);

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
                new Places.Facts("map:1", visits, errandMap, Set.of(), avoid, justBeen, Set.of()), WEIGHTS);
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

    /** A full bag makes a shopkeeper two maps away worth more than an unopened door. */
    @Test
    void aShopOutweighsCuriosityWhenTheAgentNeedsOne() {
        believe("map:2", "has_door", "north00");
        List<Places.Place> places = Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of("map:3")), WEIGHTS);

        assertEquals("map:3", places.get(0).map());
        assertTrue(places.get(0).reasons().contains("somebody who buys and sells, and you need to"));
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

    /**
     * Somewhere that killed it twice at about this level is not somewhere to go - but when it
     * is the only way on, what lies beyond it is. Walled in at Sleepywood by the dungeons on
     * either side, agents did nothing for eight hours but take NPCs' offers of passage.
     */
    @Test
    void aMapThatKeepsKillingItIsNotOfferedButWhatIsBeyondItIsWhenThereIsNothingElse() {
        believe("map:2", "has_door", "north00");
        believe("map:3", "has_door", "north00");

        List<Places.Place> places = Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of(), null, null,
                        Map.of("map:2", 2)), WEIGHTS);

        assertEquals(1, places.size(), places.toString());
        assertEquals("map:3", places.get(0).map(), "not map:2, which killed it twice");
        assertTrue(places.get(0).reasons().contains("the only way on is through somewhere that killed you"));
    }

    /** Not straight after dying there: whatever else there is to do comes first. */
    @Test
    void notThroughAKillingGroundStraightAfterDying() {
        believe("map:2", "has_door", "north00");
        believe("map:3", "has_door", "north00");

        List<Places.Place> places = Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of(), null, null,
                        Map.of("map:2", 2)), WEIGHTS, false);

        assertTrue(places.isEmpty(), places.toString());
    }

    /** A shop it needs, beyond somewhere that killed it, is worth going through for. */
    @Test
    void goesThroughDangerForAShopItNeeds() {
        believe("map:1", "has_door", "west00");     // something else worth doing on this side
        door(1, "west00", 5);
        believe("npc:7", "present_in", "map:5");
        visits.put("map:5", 3);

        List<Places.Place> places = Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of("map:3"), null, null,
                        Map.of("map:2", 2)), WEIGHTS);

        assertTrue(places.stream().anyMatch(place -> place.map().equals("map:3")
                && place.reasons().contains("somebody who buys and sells, and you need to")), places.toString());
        assertTrue(places.stream().noneMatch(place -> place.map().equals("map:2")), "not the killing ground itself");
    }

    /** A shop it needs comes before somewhere more interesting. */
    @Test
    void aShopItNeedsComesFirst() {
        believe("map:3", "has_door", "north00");
        believe("map:3", "has_door", "south00");
        believe("npc:8", "present_in", "map:3");

        List<Places.Place> places = Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of("map:2")), WEIGHTS);

        assertEquals("map:2", places.get(0).map(), places.toString());
    }

    /** With a way round, the far side of a killing ground is still somewhere to go. */
    @Test
    void goesAroundAKillingGroundWhenThereIsAWay() {
        believe("map:3", "has_door", "north00");
        door(1, "south00", 4);      // a second way: 1 -> 4 -> 3
        door(4, "east00", 3);
        visits.put("map:4", 3);

        List<Places.Place> places = Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of(), null, null,
                        Map.of("map:2", 2)), WEIGHTS);

        assertEquals("map:3", places.get(0).map());
        assertEquals("south00", places.get(0).firstDoor(), "the long way, around map:2");
    }

    private List<Places.Place> rankAtLevel(int level, Map<String, Integer> huntedAt) {
        return Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of(), null, null,
                        Map.of(), level, huntedAt), WEIGHTS);
    }

    /** Both maps have snails in them; only one is where people train at this level. */
    @Test
    void whereWatchedPeopleHuntAtThisLevelIsTheBetterGround() {
        believe("monster:100100", "present_in", "map:2");
        believe("monster:100100", "present_in", "map:3");
        // A way on from map:3, so it is not a dead end already seen all of.
        door(3, "north00", 4);
        door(4, "south00", 3);
        visits.put("map:4", 3);

        List<Places.Place> places = rankAtLevel(8, Map.of("map:3", 9));

        assertEquals("map:3", places.get(0).map(), "worth the extra door: " + places);
        assertTrue(places.get(0).reasons().contains("people hunt there at about your level"));
    }

    @Test
    void somewherePeopleOnlyHuntMuchLaterIsNotAHuntingGroundYet() {
        believe("monster:100100", "present_in", "map:2");

        List<Places.Place> places = rankAtLevel(3, Map.of("map:2", 20));

        assertTrue(places.isEmpty(), "level 3 has no business where people hunt at 20: " + places);
    }

    /**
     * Sent somewhere that has killed it, it is still offered: the instructor its trainer sent
     * it to stands in a map a predecessor died in, and the second job waits there.
     */
    @Test
    void whereItWasSentIsOfferedEvenIfItKilledIt() {
        List<Places.Place> places = Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of(), "map:3",
                        "to take what your trainer gave you", Map.of("map:3", 3)), WEIGHTS, true);

        assertTrue(places.stream().anyMatch(p -> p.map().equals("map:3")), "the instructor's map: " + places);
        assertEquals("map:3", places.get(0).map());
    }

    private List<Places.Place> rankByRate(Map<String, Double> rates, double here) {
        return Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of(), null, null,
                        Map.of(), 45, Map.of(), rates, here), WEIGHTS);
    }

    /**
     * Something alive in both, as it always was; but one is a field of pigs and the other a
     * cave of drakes. The extra door is worth it for nine times the experience.
     */
    @Test
    void theGroundThatPaysMoreIsTheBetterGround() {
        believe("monster:pig", "present_in", "map:2");
        believe("monster:drake", "present_in", "map:3");
        door(3, "north00", 4);
        door(4, "south00", 3);
        visits.put("map:4", 3);

        List<Places.Place> places = rankByRate(Map.of("map:2", 150.0, "map:3", 1350.0), 150);

        assertEquals("map:3", places.get(0).map(), "worth the extra door: " + places);
        assertTrue(places.get(0).reasons().stream().anyMatch(r -> r.startsWith("about 1350 experience a minute")),
                places.get(0).reasons().toString());
    }

    /** A field paying half of what it earns here is not a hunting ground worth walking to. */
    @Test
    void anOutgrownGroundIsHardlyWorthTheWalk() {
        believe("monster:snail", "present_in", "map:2");

        double flat = rankByRate(Map.of(), 0).get(0).value();
        double outgrown = rankByRate(Map.of("map:2", 100.0), 400).stream()
                .filter(p -> p.map().equals("map:2")).mapToDouble(Places.Place::value).findFirst().orElse(0);

        assertTrue(outgrown < flat, outgrown + " should be below " + flat);
    }

    @Test
    void doublingWhatItEarnsDoublesWhatHuntingIsWorth() {
        assertEquals(2.0, Places.worthOfRate(400, 200), 1e-9);
        assertEquals(Places.RATE_CEILING, Places.worthOfRate(10_000, 200), 1e-9);
        assertEquals(Places.RATE_FLOOR, Places.worthOfRate(50, 200), 1e-9);
    }

    @Test
    void withNobodyWatchedNothingChanges() {
        believe("monster:100100", "present_in", "map:2");

        assertEquals(rankAtLevel(8, Map.of()).stream().map(Places.Place::value).toList(),
                Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()),
                        new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of()), WEIGHTS)
                        .stream().map(Places.Place::value).toList());
    }
    /** Stalled, somewhere with somebody offering passage is worth the trip; otherwise not. */
    @Test
    void somebodyOfferingPassageIsWorthGoingToOnceStalled() {
        Places.Weights stalled = new Places.Weights(2.0, 1.5, 0.8, 3.0, 3.5, 0.6, 1.5, 0.3, 0.35, 1.0, 1.0, 2.0, 4.0);
        Places.Facts facts = new Places.Facts("map:1", visits, null, Set.of(), Set.of(), Map.of(), Set.of(), null, null,
                Map.of(), 45, Map.of(), Map.of(), 0, Set.of("map:3"));

        List<Places.Place> places = Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()), facts, stalled);

        assertEquals("map:3", places.get(0).map(), places.toString());
        assertTrue(places.get(0).reasons().contains("somebody who offers passage somewhere you have never been"));
        assertTrue(Places.worthGoing(KnownWorld.rememberedBy(memory.liveBeliefs()), facts, WEIGHTS).isEmpty(),
                "not stalled, a passage is no reason on its own");
    }
}
