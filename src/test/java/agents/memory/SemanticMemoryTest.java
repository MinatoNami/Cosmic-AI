package agents.memory;

import agents.memory.Belief.Provenance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMemoryTest {

    private final SemanticMemory memory = new SemanticMemory();

    @Test
    void seeingTheSameThingAgainRaisesConfidenceRatherThanDuplicating() {
        SemanticMemory.Assertion first =
                memory.assertTriple("npc:2101", "present_in", "map:10000", 1, 10, Provenance.FIRST_HAND);
        SemanticMemory.Assertion second =
                memory.assertTriple("npc:2101", "present_in", "map:10000", 2, 20, Provenance.FIRST_HAND);

        assertTrue(first.isNew());
        assertFalse(second.isNew());
        assertEquals(1, memory.size());
        assertTrue(second.belief().confidence() > first.belief().confidence());
        assertEquals(List.of(1L, 2L), second.belief().supportedBy());
        assertEquals(20, second.belief().lastSeen());
    }

    @Test
    void hearsayStartsLessConfidentThanSeeingIt() {
        double seen = memory.assertTriple("a", "named", "x", 1, 10, Provenance.FIRST_HAND)
                .belief().confidence();
        double heard = memory.assertTriple("b", "named", "y", 2, 10, Provenance.HEARSAY)
                .belief().confidence();

        assertTrue(heard < seen, "being told something should count for less than seeing it");
    }

    /**
     * The revision case the whole bitemporal arrangement exists for: the old belief has to
     * survive, marked, pointing at what replaced it.
     */
    @Test
    void aNewValueForAFunctionalPredicateSupersedesTheOldOne() {
        Belief inTown = memory.assertTriple("self", "in_map", "map:10000", 1, 10, Provenance.FIRST_HAND)
                .belief();
        SemanticMemory.Assertion moved =
                memory.assertTriple("self", "in_map", "map:104000000", 2, 50, Provenance.FIRST_HAND);

        assertEquals(inTown.id(), moved.contradicted().id());
        assertEquals(1, memory.liveBeliefs().size());
        assertEquals(2, memory.size(), "the old belief should still be there, not deleted");

        Belief stored = memory.byId(inTown.id()).orElseThrow();
        assertFalse(stored.isLive());
        assertEquals(50, stored.invalidatedAt());
        assertEquals(moved.belief().id(), stored.supersededBy());
    }

    /**
     * Most predicates are not exclusive, and treating an unknown one as if it were would
     * silently destroy true beliefs - a monster really can drop more than one thing.
     */
    @Test
    void anUnknownPredicateAccumulatesValuesInsteadOfReplacingThem() {
        memory.assertTriple("monster:100100", "drops", "item:2000000", 1, 10, Provenance.FIRST_HAND);
        memory.assertTriple("monster:100100", "drops", "item:4000019", 2, 20, Provenance.FIRST_HAND);

        assertEquals(2, memory.liveBeliefs().size());
        assertEquals(2, memory.about("monster:100100").size());
    }

    @Test
    void aBeliefWithoutEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new Belief(0, "a", "b", "c", 0.5, Provenance.FIRST_HAND, List.of(), 1, 1, null, null));
    }
}
