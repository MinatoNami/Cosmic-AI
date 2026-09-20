package agents.memory;

import agents.percept.Observation;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeliefFormerTest {

    private final BeliefFormer former = new BeliefFormer();

    private List<BeliefFormer.Triple> from(Observation observation) {
        return former.beliefsFrom(new Episode(0, observation.tick(), observation));
    }

    @Test
    void restatesWhatAnObservationSaysOutright() {
        List<BeliefFormer.Triple> triples =
                from(new Observation.SelfDescribed(1, 2, "Agent0", 1, 0, 10000));

        assertTrue(triples.contains(BeliefFormer.Triple.firstHand("self", "named", "Agent0")));
        assertTrue(triples.contains(BeliefFormer.Triple.firstHand("self", "in_map", "map:10000")));
    }

    @Test
    void locatesSightingsInWhateverMapTheAgentIsIn() {
        from(new Observation.MapEntered(1, 104000000, 0));

        List<BeliefFormer.Triple> triples =
                from(new Observation.MonsterAppeared(2, 9001, 100100, new Point(0, 0)));

        assertEquals(List.of(BeliefFormer.Triple.firstHand(
                "monster:100100", "present_in", "map:104000000")), triples);
    }

    /**
     * The restraint is the point, so it gets a test. A rule that concluded a monster was
     * dangerous, or that an NPC sold something, would make the demo look better while
     * removing the thing the project exists to observe an agent working out for itself.
     */
    @Test
    void concludesNothingBeyondTheObservation() {
        List<BeliefFormer.Triple> triples =
                from(new Observation.MonsterAppeared(1, 9001, 100100, new Point(50, 60)));

        assertEquals(1, triples.size());
        assertEquals("present_in", triples.get(0).predicate());
        assertTrue(triples.stream().noneMatch(t ->
                        t.predicate().contains("danger") || t.predicate().contains("weak")),
                "a monster's nature is something the agent has to learn, not something it is told");
    }

    /**
     * Positions change several times a second. Holding a long-term belief about one would
     * churn the graph and teach nothing; the episode is the right home for it.
     */
    @Test
    void formsNoBeliefAboutSomethingAsVolatileAsAPosition() {
        assertTrue(from(new Observation.ThingMoved(1, 3, new Point(10, 20))).isEmpty());
    }

    @Test
    void recordsWhatAnotherPlayerSaidAsSomethingTheySaid() {
        List<BeliefFormer.Triple> triples =
                from(new Observation.ChatHeard(1, 3, "the slimes are east"));

        assertEquals(List.of(BeliefFormer.Triple.firstHand(
                        "player:3", "said", "the slimes are east")), triples,
                "hearing a claim is a fact about the speaker, not yet a fact about the world");
    }

    /**
     * Map chat echoes your own messages back. Recording "player:2 said ..." about yourself is
     * noise, and worse, it makes an agent look like it learned something from someone else.
     */
    @Test
    void doesNotRecordItselfAsASource() {
        from(new Observation.SelfDescribed(1, 2, "Agent0", 1, 0, 10000));

        assertTrue(from(new Observation.ChatHeard(2, 2, "!know monster:1 present_in map:2")).isEmpty(),
                "an agent hearing its own voice has learned nothing");
        assertFalse(from(new Observation.ChatHeard(3, 9, "!know monster:1 present_in map:2")).isEmpty(),
                "someone else saying it is still worth recording");
    }

    @Test
    void namesEachChangedStat() {
        List<BeliefFormer.Triple> triples =
                from(new Observation.StatsChanged(1, Map.of("HP", 50)));

        assertEquals(List.of(BeliefFormer.Triple.firstHand("self", "hp", "50")), triples);
    }
}
