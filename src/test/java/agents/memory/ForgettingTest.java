package agents.memory;

import agents.Mind;
import agents.percept.Observation;
import agents.trace.Trace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Letting go of what nothing rests on, without losing anything a belief does.
 *
 * Minds had grown to 780MB and the daemon to a full 4GB heap, because every episode was kept
 * for ever and every belief kept every sighting behind it.
 */
class ForgettingTest {

    @TempDir
    Path dir;

    /** A belief seen a thousand times keeps sixteen sightings, and is exactly as sure. */
    @Test
    void aBeliefKeepsItsFirstAndLatestEvidenceAndLosesNoConfidence() {
        SemanticMemory memory = new SemanticMemory();
        for (long episode = 0; episode < 1000; episode++) {
            memory.assertTriple("map:1", "has_door", "east00", episode, episode, Belief.Provenance.HEARSAY);
        }

        Belief belief = memory.liveBeliefs().get(0);
        assertEquals(Belief.EVIDENCE_KEPT, belief.supportedBy().size());
        assertEquals(java.util.List.of(0L, 1L, 2L, 3L), belief.supportedBy().subList(0, 4),
                "the first sightings are the ones worth walking back to");
        assertEquals(999L, belief.supportedBy().get(belief.supportedBy().size() - 1));
        assertEquals(0.99, belief.confidence(), 1e-9, "sixteen sightings is already as sure as it gets");
    }

    /** Old uncited episodes go; cited ones and the recent past stay; ids never shift. */
    @Test
    void forgetsTheMiddleAndKeepsIdsWhereTheyWere() {
        EpisodicMemory memory = new EpisodicMemory();
        for (int i = 0; i < 100; i++) {
            memory.record(new Observation.MapEntered(i, 10000 + i, 0));
        }

        int dropped = memory.forgetAllBut(Set.of(5L, 17L), 10);

        assertEquals(88, dropped, "100 minus the two cited and the last ten");
        assertTrue(memory.byId(5).isPresent() && memory.byId(17).isPresent());
        assertTrue(memory.byId(6).isEmpty());
        assertEquals(10005, ((Observation.MapEntered) memory.byId(5).orElseThrow().observation()).mapId(),
                "episode 5 is still episode 5");
        assertEquals(100, memory.record(new Observation.MapEntered(100, 1, 0)).id(),
                "the next id carries on from the last, not from how many are left");
    }

    /**
     * The failure that would be worst: a belief's evidence quietly pointing at somebody else's
     * episode after a save and a load with gaps in it.
     */
    @Test
    void aMindThatHasForgottenSavesAndWakesWithEvidenceIntact() {
        Mind mind = new Mind("Test", Trace.toFile(dir.resolve("t.jsonl"), "Test"));
        mind.take(new Observation.NpcAppeared(1, 700, 2100, new Point(10, 0)));
        for (int i = 0; i < Mind.RECENT_EPISODES + 500; i++) {
            mind.take(new Observation.ThingMoved(2 + i, 9001, new Point(i % 300, 0)));
        }
        Belief npc = mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals("npc:2100")).findFirst().orElseThrow();
        long evidence = npc.supportedBy().get(0);

        Path saved = dir.resolve("Test.mind");
        mind.save(saved, 99);
        assertTrue(mind.episodic().size() < Mind.RECENT_EPISODES + 500, "nothing was let go");

        Mind woken = new Mind("Test", Trace.toFile(dir.resolve("t2.jsonl"), "Test"));
        woken.restoreFrom(saved);
        Belief again = woken.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals("npc:2100")).findFirst().orElseThrow();
        assertEquals(evidence, again.supportedBy().get(0));
        assertTrue(woken.episodic().byId(evidence).orElseThrow().observation().toString().contains("2100"),
                "the belief's evidence is no longer the episode it was");
    }
}
