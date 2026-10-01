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

    /**
     * A mind is read from other threads while its agent writes to it. With a plain list, a
     * reader walking live beliefs during a write threw ConcurrentModificationException, and
     * one thrown inside an agent's step ended that agent.
     */
    @Test
    void canBeReadWhileItIsBeingWritten() throws Exception {
        SemanticMemory memory = new SemanticMemory();
        java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < 2_000; i++) {
                    memory.liveBeliefs().stream().filter(b -> b.predicate().equals("p")).count();
                }
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        reader.start();
        for (int i = 0; i < 4_000; i++) {
            memory.assertTriple("s" + i, "p", "o", i, i, Belief.Provenance.FIRST_HAND);
            memory.assertTriple("s" + (i / 2), "p", "o", i, i, Belief.Provenance.FIRST_HAND);
        }
        reader.join();

        org.junit.jupiter.api.Assertions.assertNull(failed.get(), "a reader failed while the agent wrote");
    }

    /**
     * A potion that seemed to do nothing once - drunk while something hit harder than it
     * healed - must not stay believed useless beside the time it worked, or it gets sold.
     */
    @Test
    void theLatestTryOverrulesWhatAnItemWasThoughtToDo() {
        memory.assertTriple("item:2000000", "restores_hp", "true", 1, 10, Provenance.INFERRED);
        SemanticMemory.Assertion later =
                memory.assertTriple("item:2000000", "restores_hp", "false", 2, 20, Provenance.INFERRED);

        assertTrue(later.contradicted() != null);
        assertEquals(1, memory.liveBeliefs().stream()
                .filter(b -> b.subject().equals("item:2000000") && b.predicate().equals("restores_hp"))
                .count());
    }

    /** An agent at DEX 120 held 116 beliefs about its DEX. A character has one. */
    @Test
    void aCharacterHasOneOfEachStat() {
        memory.assertTriple("self", "dex", "5", 1, 10, Provenance.FIRST_HAND);
        memory.assertTriple("self", "dex", "6", 2, 20, Provenance.FIRST_HAND);

        assertEquals(List.of("6"), memory.liveBeliefs().stream()
                .filter(b -> b.predicate().equals("dex")).map(b -> b.object()).toList());
    }
}
