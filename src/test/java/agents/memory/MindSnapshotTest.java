package agents.memory;

import agents.percept.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindSnapshotTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("Agent0.mind");
    }

    /** Fills a pair of memories the way a run would, and returns them. */
    private static EpisodicMemory episodesWith(String... chats) {
        EpisodicMemory episodic = new EpisodicMemory();
        long tick = 1;
        for (String chat : chats) {
            episodic.record(new Observation.ChatHeard(tick++, 7, chat));
        }
        return episodic;
    }

    @Test
    void carriesBeliefsAndTheirEvidenceAcrossARun() {
        EpisodicMemory episodic = episodesWith("hello", "there");
        SemanticMemory semantic = new SemanticMemory();
        semantic.assertTriple("npc:2000", "present_in", "map:10000", 0, 1, Belief.Provenance.FIRST_HAND);
        semantic.assertTriple("npc:2000", "present_in", "map:10000", 1, 2, Belief.Provenance.FIRST_HAND);

        MindSnapshot.save(file(), "Agent0", 42, episodic, semantic);

        EpisodicMemory reloadedEpisodic = new EpisodicMemory();
        SemanticMemory reloadedSemantic = new SemanticMemory();
        MindSnapshot.Restored restored = MindSnapshot.load(file(), reloadedEpisodic, reloadedSemantic);

        assertNotNull(restored);
        assertEquals("Agent0", restored.agent());
        assertEquals(42, restored.tick());
        assertEquals(2, restored.episodes());
        assertEquals(1, restored.beliefs());

        Belief belief = reloadedSemantic.liveBeliefs().get(0);
        assertEquals("npc:2000", belief.subject());
        assertEquals("present_in", belief.predicate());
        assertEquals("map:10000", belief.object());
        assertEquals(List.of(0L, 1L), belief.supportedBy());
        assertEquals(semantic.liveBeliefs().get(0).confidence(), belief.confidence());
        // The evidence has to still be there, or the belief is pointing at nothing.
        assertTrue(reloadedEpisodic.byId(0).isPresent());
        assertTrue(reloadedEpisodic.byId(1).isPresent());
    }

    /**
     * Chat is a field an agent does not control, and the format is tab separated, so a tab in
     * something someone said must not be able to end a record early.
     */
    @Test
    void survivesTextContainingTabsAndNewlines() {
        EpisodicMemory episodic = episodesWith("two\tcolumns\nand a second line");
        SemanticMemory semantic = new SemanticMemory();
        semantic.assertTriple("player:7", "said", "two\tcolumns\nand a second line",
                0, 1, Belief.Provenance.FIRST_HAND);

        MindSnapshot.save(file(), "Agent0", 1, episodic, semantic);

        EpisodicMemory reloadedEpisodic = new EpisodicMemory();
        SemanticMemory reloadedSemantic = new SemanticMemory();
        MindSnapshot.Restored restored = MindSnapshot.load(file(), reloadedEpisodic, reloadedSemantic);

        assertEquals(1, restored.beliefs());
        assertEquals(1, restored.episodes());
        assertEquals("two\tcolumns\nand a second line", reloadedSemantic.liveBeliefs().get(0).object());
    }

    /** A revised belief stays revised, or a resumed agent re-learns what it already unlearned. */
    @Test
    void keepsRevisionsRevised() {
        EpisodicMemory episodic = episodesWith("a", "b");
        SemanticMemory semantic = new SemanticMemory();
        semantic.assertTriple("self", "level", "1", 0, 1, Belief.Provenance.FIRST_HAND);
        semantic.assertTriple("self", "level", "2", 1, 9, Belief.Provenance.FIRST_HAND);

        MindSnapshot.save(file(), "Agent0", 9, episodic, semantic);

        SemanticMemory reloaded = new SemanticMemory();
        MindSnapshot.load(file(), new EpisodicMemory(), reloaded);

        assertEquals(2, reloaded.all().size());
        assertEquals(1, reloaded.liveBeliefs().size());
        assertEquals("2", reloaded.liveBeliefs().get(0).object());
        Belief dead = reloaded.byId(0).orElseThrow();
        assertEquals(9L, dead.invalidatedAt());
        assertEquals(1L, dead.supersededBy());
    }

    @Test
    void aFirstRunHasNoMindToRestore() {
        assertNull(MindSnapshot.load(file(), new EpisodicMemory(), new SemanticMemory()));
    }

    /** Ids are list positions, so restoring over a live mind would repoint evidence silently. */
    @Test
    void refusesToRestoreOverAMindThatAlreadyHasSomethingInIt() {
        EpisodicMemory episodic = episodesWith("a");
        SemanticMemory semantic = new SemanticMemory();
        semantic.assertTriple("npc:1", "present_in", "map:1", 0, 1, Belief.Provenance.FIRST_HAND);
        MindSnapshot.save(file(), "Agent0", 1, episodic, semantic);

        assertThrows(IllegalStateException.class,
                () -> MindSnapshot.load(file(), episodic, semantic));
    }
}
