package agents.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InheritanceTest {

    @TempDir
    Path minds;

    /** Writes a mind file the way a stopped agent would have left one. */
    private Path mindOf(String agent, String[][] triples) {
        EpisodicMemory episodic = new EpisodicMemory();
        episodic.restore(1, "MapEntered", "MapEntered[mapId=10000]");
        SemanticMemory semantic = new SemanticMemory();
        for (String[] t : triples) {
            semantic.assertTriple(t[0], t[1], t[2], 0, 1, Belief.Provenance.FIRST_HAND);
        }
        Path file = minds.resolve(agent + ".mind");
        MindSnapshot.save(file, agent, 500, episodic, semantic);
        return file;
    }

    private SemanticMemory read(Path file) {
        EpisodicMemory episodic = new EpisodicMemory();
        SemanticMemory semantic = new SemanticMemory();
        MindSnapshot.load(file, episodic, semantic);
        return semantic;
    }

    private boolean holds(SemanticMemory memory, String subject, String predicate, String object) {
        return memory.liveBeliefs().stream().anyMatch(b -> b.subject().equals(subject)
                && b.predicate().equals(predicate) && b.object().equals(object));
    }

    @Test
    void keepsWhatIsTrueAboutTheWorld() {
        Path one = mindOf("Agent0", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"},
                {"map:10000", "has_door", "east00"},
                {"npc:22000", "wants_first", "150 mesos"},
                {"monster:100100", "drops", "item:2000000"},
        });

        Inheritance.Merged merged = Inheritance.merge(List.of(one),
                minds.resolve("inherited.mind"), "Inherited");

        assertEquals(4, merged.beliefsKept());
        SemanticMemory inherited = read(minds.resolve("inherited.mind"));
        assertTrue(holds(inherited, "portal:10000/east00", "leads_to", "map:20000"));
        assertTrue(holds(inherited, "npc:22000", "wants_first", "150 mesos"));
    }

    /**
     * A body is not knowledge. Waking up believing you are level twenty-three, in a map you
     * are not in, with mesos you do not have, would be a mind that is wrong about itself
     * from its first decision.
     */
    @Test
    void forgetsEverythingAboutTheBodyItNoLongerHas() {
        Path one = mindOf("Agent0", new String[][]{
                {"self", "level", "23"},
                {"self", "in_map", "map:104020000"},
                {"self", "meso", "9000"},
                {"self", "named", "Agent0"},
                {"player:41", "in_map", "map:104000000"},
                {"portal:10000/east00", "leads_to", "map:20000"},
        });

        Inheritance.merge(List.of(one), minds.resolve("inherited.mind"), "Inherited");

        SemanticMemory inherited = read(minds.resolve("inherited.mind"));
        assertEquals(1, inherited.liveBeliefs().size(),
                "only the door is about the world; the rest was about a character that is gone");
        assertFalse(holds(inherited, "self", "level", "23"));
        assertFalse(holds(inherited, "player:41", "in_map", "map:104000000"));
    }

    /**
     * The new agent has never been to any of these places. Calling that first-hand in a
     * memory built so provenance cannot lie would be the one unforgivable entry.
     */
    @Test
    void inheritsAsHearsayBecauseItHasSeenNoneOfIt() {
        Path one = mindOf("Agent0", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"}});

        Inheritance.merge(List.of(one), minds.resolve("inherited.mind"), "Inherited");

        assertEquals(Belief.Provenance.HEARSAY,
                read(minds.resolve("inherited.mind")).liveBeliefs().getFirst().provenance());
    }

    @Test
    void twoAgentsThatAgreeProduceOneBeliefTrustedMore() {
        Path one = mindOf("Agent0", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"}});
        Path two = mindOf("Agent1", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"},
                {"portal:20000/west00", "leads_to", "map:10000"}});

        Inheritance.Merged merged = Inheritance.merge(List.of(one, two),
                minds.resolve("inherited.mind"), "Inherited");

        assertEquals(2, merged.beliefsKept(), "two distinct doors between them");
        assertEquals(1, merged.agreed(), "and one they both found");
        SemanticMemory inherited = read(minds.resolve("inherited.mind"));
        Belief shared = inherited.liveBeliefs().stream()
                .filter(b -> b.subject().equals("portal:10000/east00")).findFirst().orElseThrow();
        assertEquals(2, shared.supportedBy().size(),
                "corroboration is what makes a merged belief worth more than either copy");
    }

    /** Disagreement keeps both claims; which one wins is decided where it is used. */
    @Test
    void keepsBothSidesOfADisagreement() {
        Path one = mindOf("Agent0", new String[][]{
                {"portal:10000/tuto00", "leads_to", "nowhere"}});
        Path two = mindOf("Agent1", new String[][]{
                {"portal:10000/tuto00", "leads_to", "map:30000"}});

        Inheritance.merge(List.of(one, two), minds.resolve("inherited.mind"), "Inherited");

        SemanticMemory inherited = read(minds.resolve("inherited.mind"));
        assertTrue(holds(inherited, "portal:10000/tuto00", "leads_to", "nowhere"));
        assertTrue(holds(inherited, "portal:10000/tuto00", "leads_to", "map:30000"));
    }

    /** Merging is cumulative, so generation one outlives generation four's forgetfulness. */
    @Test
    void foldsTheStandingInheritanceBackIn() {
        Path old = mindOf("Agent0", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"}});
        Inheritance.merge(List.of(old), minds.resolve("inherited.mind"), "Inherited");

        Path next = mindOf("Agent1", new String[][]{
                {"portal:20000/east00", "leads_to", "map:30000"}});
        Inheritance.merge(List.of(next, minds.resolve("inherited.mind")),
                minds.resolve("inherited.mind"), "Inherited");

        SemanticMemory inherited = read(minds.resolve("inherited.mind"));
        assertTrue(holds(inherited, "portal:10000/east00", "leads_to", "map:20000"),
                "what the first generation learned must survive the second");
        assertTrue(holds(inherited, "portal:20000/east00", "leads_to", "map:30000"));
    }

    @Test
    void startsTheNextGenerationsClockAtTheBeginning() {
        Path one = mindOf("Agent0", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"}});

        Inheritance.merge(List.of(one), minds.resolve("inherited.mind"), "Inherited");

        EpisodicMemory episodic = new EpisodicMemory();
        MindSnapshot.Restored restored =
                MindSnapshot.load(minds.resolve("inherited.mind"), episodic, new SemanticMemory());
        assertEquals(0, restored.tick(), "a new life, not a continuation of somebody else's");
        assertEquals(1, restored.episodes(),
                "one predecessor, so one moment of being told for the beliefs to rest on");
    }

    @Test
    void doesNotCountTheInheritanceAsOneOfTheMindsToMerge() {
        mindOf("Agent0", new String[][]{{"map:10000", "has_door", "east00"}});
        MindSnapshot.save(minds.resolve("inherited.mind"), "Inherited", 0,
                new EpisodicMemory(), new SemanticMemory());

        List<Path> found = Inheritance.mindsIn(minds, "inherited.mind");

        assertEquals(1, found.size());
        assertEquals("Agent0.mind", found.getFirst().getFileName().toString());
    }
}
