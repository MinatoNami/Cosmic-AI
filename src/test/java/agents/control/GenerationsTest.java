package agents.control;

import agents.memory.Belief;
import agents.memory.EpisodicMemory;
import agents.memory.MindSnapshot;
import agents.memory.SemanticMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationsTest {

    @TempDir
    Path data;

    private void mindOf(String file, String[][] triples) throws Exception {
        Files.createDirectories(data.resolve("minds"));
        EpisodicMemory episodic = new EpisodicMemory();
        episodic.restore(1, "MapEntered", "MapEntered[mapId=10000]");
        SemanticMemory semantic = new SemanticMemory();
        for (String[] t : triples) {
            semantic.assertTriple(t[0], t[1], t[2], 0, 1, Belief.Provenance.FIRST_HAND);
        }
        MindSnapshot.save(data.resolve("minds").resolve(file), file, 500, episodic, semantic);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCondenseIsRecordedWithWhatEachSourceBrought() throws Exception {
        mindOf("Agent0.mind", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"},
                {"npc:22000", "runs_shop", "shop:1"},
        });
        mindOf("Agent1.mind", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"},
        });
        mindOf(Population.INHERITANCE, new String[][]{
                {"map:10000", "has_door", "east00"},
        });
        Population population = new Population("localhost", 0, data);

        population.condense();

        List<Map<String, Object>> events = population.generations().read();
        assertEquals(1, events.size());
        Map<String, Object> condense = events.get(0);
        assertEquals("condense", condense.get("event"));
        assertEquals(3, condense.get("mindsRead"));
        assertEquals(3, condense.get("inherited"));
        assertEquals(1, condense.get("agreedOn"));

        List<Map<String, Object>> sources = (List<Map<String, Object>>) condense.get("sources");
        assertEquals("Agent0.mind", sources.get(0).get("file"));
        assertEquals(2, sources.get(0).get("new"));
        assertEquals("Agent1.mind", sources.get(1).get("file"));
        assertEquals(0, sources.get(1).get("new"));
        assertEquals(1, sources.get(1).get("agreed"));
        assertTrue(((Number) sources.get(0).get("bytes")).longValue() > 0);

        // The inheritance it replaced is kept, outside the minds directory.
        String archived = (String) condense.get("previousInheritance");
        assertNotNull(archived);
        assertTrue(Files.isRegularFile(data.resolve(archived)));
        assertEquals(1, Files.list(data.resolve("minds"))
                .filter(p -> p.getFileName().toString().startsWith("inherited")).count());
    }

    @Test
    void aFirstCondenseHasNothingToArchive() throws Exception {
        mindOf("Agent0.mind", new String[][]{{"map:10000", "has_door", "east00"}});
        Population population = new Population("localhost", 0, data);

        population.condense();

        Map<String, Object> condense = population.generations().read().get(0);
        assertEquals(null, condense.get("previousInheritance"));
        assertEquals(0, condense.get("inheritanceBytesBefore"));
        assertFalse(Files.exists(data.resolve(Generations.PAST_INHERITANCES)));
    }

    @Test
    void forgettingReportsWhichMindsWentAndKeepsTheInheritance() throws Exception {
        mindOf("Agent0.mind", new String[][]{{"map:10000", "has_door", "east00"}});
        mindOf(Population.INHERITANCE, new String[][]{{"map:10000", "has_door", "east00"}});
        Population population = new Population("localhost", 0, data);

        List<Generations.MindFile> forgotten = population.forgetMinds();

        assertEquals(1, forgotten.size());
        assertEquals("Agent0.mind", forgotten.get(0).file());
        assertTrue(forgotten.get(0).bytes() > 0);
        assertTrue(Files.isRegularFile(data.resolve("minds").resolve(Population.INHERITANCE)));
    }

    @Test
    void eventsAreReadBackInOrderAndATornLineIsSkipped() throws Exception {
        Generations ledger = new Generations(data);
        ledger.record("reset", Map.of("mindsForgotten",
                List.of(new Generations.MindFile("Agent0.mind", 10))));
        Files.writeString(ledger.file(), "{\"at\":", java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(ledger.file(), "\n", java.nio.file.StandardOpenOption.APPEND);
        ledger.record("condense", Map.of("inherited", 5));

        List<Map<String, Object>> events = ledger.read();

        assertEquals(2, events.size());
        assertEquals("reset", events.get(0).get("event"));
        assertEquals("condense", events.get(1).get("event"));
        assertEquals(List.of(Map.of("file", "Agent0.mind", "bytes", 10)),
                events.get(0).get("mindsForgotten"));
    }

    @Test
    void twoInheritancesAreComparedByWhatTheySay() throws Exception {
        mindOf("Agent0.mind", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"},
                {"npc:22000", "runs_shop", "shop:1"},
        });
        Population population = new Population("localhost", 0, data);
        population.condense();
        // Pretend the first inheritance was made an hour ago, so the second gets its own name.
        Path standing = data.resolve("minds").resolve(Population.INHERITANCE);
        Files.setLastModifiedTime(standing, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minusSeconds(3600)));

        population.forgetMinds();
        mindOf("Agent0.mind", new String[][]{
                {"portal:10000/east00", "leads_to", "map:20000"},
                {"monster:100100", "drops", "item:2000000"},
        });
        population.condense();

        List<Generations.Snapshot> snapshots = population.inheritances();
        assertEquals(2, snapshots.size());
        assertEquals(Generations.STANDING, snapshots.get(1).name());

        Population.InheritanceDiff diff = population.compare(snapshots.get(0).name(), "current");
        assertEquals(2, diff.beliefsBefore());
        assertEquals(3, diff.beliefsAfter());
        assertEquals(List.of("drops"), diff.added().stream().map(Population.BeliefView::predicate).toList());
        assertTrue(diff.removed().isEmpty(), "the standing inheritance is a source, so nothing is lost");
        // Said again by the new generation, so it now rests on two sources rather than one.
        assertEquals(1, diff.changed().size());
        assertEquals("leads_to", diff.changed().get(0).belief().predicate());
        assertEquals(1, diff.changed().get(0).evidenceBefore());
        assertEquals(1, diff.unchanged());
    }

    @Test
    void onlyArchivedNamesCanBeCompared() throws Exception {
        mindOf("Agent0.mind", new String[][]{{"map:10000", "has_door", "east00"}});
        Population population = new Population("localhost", 0, data);
        population.condense();

        assertEquals(null, population.compare("../minds/Agent0.mind", "current"));
        assertEquals(null, population.compare("inherited.20990101T000000Z.mind", "current"));
        assertNotNull(population.compare("current", "current"));
    }
}
