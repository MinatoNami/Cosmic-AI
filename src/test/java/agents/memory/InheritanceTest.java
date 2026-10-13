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

    /** What the last generation learnt the hard way is what a newborn most needs. */
    @Test
    void passesOnWhereItDiedWhatHealsAndWhoIsNoUse() {
        Path agent = mindOf("Agent0", new String[][]{
                {"map:105050000", "killed_you_at_level", "18"},
                {"npc:1012004", "runs_shop", "true"},
                {"npc:1012004", "sells", "item:2120000"},
                {"item:2000000", "restores_hp", "true"},
                {"npc:12100", "does_not_answer", "true"},
                {"npc:10203", "strands_you", "true"},
                {"self", "trained_by", "npc:1022000"},
                {"npc:1012004", "shop_was_no_use", "392"}});

        Inheritance.merge(java.util.List.of(agent), minds.resolve("inherited.mind"), "Inherited");
        SemanticMemory inherited = read(minds.resolve("inherited.mind"));

        assertTrue(holds(inherited, "map:105050000", "killed_you_at_level", "18"));
        assertTrue(holds(inherited, "npc:1012004", "sells", "item:2120000"));
        assertTrue(holds(inherited, "item:2000000", "restores_hp", "true"));
        assertTrue(holds(inherited, "npc:12100", "does_not_answer", "true"));
        assertTrue(holds(inherited, "npc:10203", "strands_you", "true"));
        assertFalse(holds(inherited, "self", "trained_by", "npc:1022000"), "about a body that is gone");
        assertFalse(holds(inherited, "npc:1012004", "shop_was_no_use", "392"), "about money it no longer has");
    }

    @Test
    void whatASourceAddedIsMeasuredAgainstTheLastInheritanceNotTheMergeOrder() {
        Path old = mindOf("Old", new String[][]{{"map:10000", "has_door", "east00"}});
        Path standing = minds.resolve("inherited.mind");
        Inheritance.merge(List.of(old), standing, "Inherited");
        Path first = mindOf("Agent0", new String[][]{
                {"map:10000", "has_door", "east00"},
                {"map:10000", "has_door", "west00"},
        });
        Path second = mindOf("Agent1", new String[][]{{"map:10000", "has_door", "west00"}});

        Inheritance.Merged merged = Inheritance.merge(List.of(first, second, standing),
                standing, "Inherited", standing);

        Inheritance.Contribution agent0 = merged.sources().get(0);
        Inheritance.Contribution agent1 = merged.sources().get(1);
        Inheritance.Contribution line = merged.sources().get(2);
        assertEquals(2, agent0.beliefsKept(), "first in the merge, so first to say both");
        assertEquals(1, agent0.notInBaseline(), "but only the west door was news");
        assertEquals(1, agent1.notInBaseline());
        assertEquals(0, line.notInBaseline());
    }

    /** A mind born holding an inheritance: these beliefs rest only on what it was told. */
    private Path bornKnowing(String agent, String[][] witnessed, String[][] sawItself) {
        EpisodicMemory episodic = new EpisodicMemory();
        // Only a mind that saw something has an episode of its own; an inheritance has none.
        long saw = sawItself.length == 0 ? -1
                : episodic.restore(1, "MapEntered", "MapEntered[mapId=10000]").id();
        SemanticMemory semantic = new SemanticMemory();
        for (String[] t : witnessed) {
            for (int i = 3; i < t.length; i++) {
                long told = episodic.restore(0, "Inherited", "Inherited[from " + t[i] + "]").id();
                semantic.assertTriple(t[0], t[1], t[2], told, 0, Belief.Provenance.HEARSAY);
            }
        }
        for (String[] t : sawItself) {
            semantic.assertTriple(t[0], t[1], t[2], saw, 1, Belief.Provenance.FIRST_HAND);
        }
        Path file = minds.resolve(agent + ".mind");
        MindSnapshot.save(file, agent, 500, episodic, semantic);
        return file;
    }

    private int evidence(SemanticMemory memory, String subject, String predicate, String object) {
        return memory.liveBeliefs().stream().filter(b -> b.subject().equals(subject)
                && b.predicate().equals(predicate) && b.object().equals(object))
                .mapToInt(b -> b.supportedBy().size()).findFirst().orElse(0);
    }

    @Test
    void aPreviousInheritanceKeepsEveryWitnessItHad() {
        Path old = bornKnowing("inherited", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind", "Agent1.mind", "Agent2.mind"},
        }, new String[0][]);
        Path agent = mindOf("Agent0", new String[][]{{"map:20000", "has_door", "west00"}});

        Inheritance.merge(List.of(agent, old), minds.resolve("next.mind"), "Inherited", old, "test");

        assertEquals(3, evidence(read(minds.resolve("next.mind")), "map:10000", "has_door", "east00"),
                "three agents said it; merging again must not make that one");
    }

    @Test
    void anAgentRepeatingWhatItWasToldIsNotAnotherWitness() {
        Path old = bornKnowing("inherited", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind", "Agent1.mind"},
        }, new String[0][]);
        Path told = bornKnowing("Agent0", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind", "Agent1.mind"},
        }, new String[0][]);

        Inheritance.merge(List.of(told, old), minds.resolve("next.mind"), "Inherited", old, "test");

        assertEquals(2, evidence(read(minds.resolve("next.mind")), "map:10000", "has_door", "east00"));
    }

    @Test
    void anAgentThatSawItForItselfIsOneMoreWitness() {
        Path old = bornKnowing("inherited", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind", "Agent1.mind"},
        }, new String[0][]);
        Path saw = bornKnowing("Agent0", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind", "Agent1.mind"},
        }, new String[][]{{"map:10000", "has_door", "east00"}});

        Inheritance.merge(List.of(saw, old), minds.resolve("next.mind"), "Inherited", old, "test");

        assertEquals(3, evidence(read(minds.resolve("next.mind")), "map:10000", "has_door", "east00"),
                "its own sighting, named for this merge, joins the two it was told by");
    }

    @Test
    void foldingInAGenerationNeverWeakensABelief() {
        Path standing = bornKnowing("inherited", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind", "Agent1.mind", "Agent2.mind", "inherited.mind"},
                {"map:10000", "has_door", "west00", "Agent0.mind"},
        }, new String[0][]);
        // An older generation's agent, born holding an older inheritance, that never went west.
        Path older = bornKnowing("Agent0", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind"},
        }, new String[][]{{"monster:100100", "gives_exp", "3"}});

        Inheritance.Merged merged = Inheritance.merge(List.of(older, standing),
                minds.resolve("next.mind"), "Inherited", standing, "minds.backup-2026-10-03-generation");

        SemanticMemory next = read(minds.resolve("next.mind"));
        assertEquals(4, evidence(next, "map:10000", "has_door", "east00"));
        assertEquals(1, evidence(next, "map:10000", "has_door", "west00"));
        assertEquals(1, evidence(next, "monster:100100", "gives_exp", "3"));
        assertEquals(3, merged.beliefsKept());
    }

    @Test
    void aRebuiltChainDoesNotCountAnAgentForWhatItWasBornKnowing() {
        // The agent was born under an old label for the same witness the rebuilt inheritance
        // names differently. Matched on labels it would read as a second witness.
        Path rebuilt = bornKnowing("inherited", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind, minds.backup-2026-09-28-generation"},
        }, new String[0][]);
        Path agent = bornKnowing("Agent0", new String[][]{
                {"map:10000", "has_door", "east00", "Agent0.mind"},
                {"map:10000", "has_door", "north00", "Agent0.mind"},
        }, new String[][]{{"map:20000", "has_door", "west00"}});

        Inheritance.merge(List.of(agent, rebuilt), minds.resolve("next.mind"), "Inherited",
                rebuilt, "minds.backup-2026-10-03-generation");

        SemanticMemory next = read(minds.resolve("next.mind"));
        assertEquals(1, evidence(next, "map:10000", "has_door", "east00"));
        // Not in any inheritance here, so what it was told is all there is: kept, as told.
        assertEquals(1, evidence(next, "map:10000", "has_door", "north00"));
    }
}
