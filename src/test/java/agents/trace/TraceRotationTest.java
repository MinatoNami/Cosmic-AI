package agents.trace;

import agents.memory.Episode;
import agents.percept.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Traces grew to 20GB. Now they roll over, compressed, and only the last few are kept. */
class TraceRotationTest {

    @TempDir
    Path dir;

    @Test
    void rollsOverCompressedAndKeepsOnlyTheLastFew() throws IOException {
        Path file = dir.resolve("Agent0.jsonl");
        Trace trace = Trace.toFile(file, "Agent0", 4_000);
        for (int i = 0; i < 2_000; i++) {
            trace.observed(new Episode(i, i, new Observation.MapEntered(i, 10000 + i, 0)));
        }
        trace.close();

        assertTrue(Files.exists(dir.resolve("Agent0.1.jsonl.gz")));
        assertTrue(Files.exists(dir.resolve("Agent0.3.jsonl.gz")));
        assertFalse(Files.exists(dir.resolve("Agent0.4.jsonl.gz")), "only three are kept");
        assertTrue(Files.size(file) < 4_000 + 500, "the live file started again");

        String rolled;
        try (var in = new GZIPInputStream(Files.newInputStream(dir.resolve("Agent0.1.jsonl.gz")))) {
            rolled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(rolled.contains("\"kind\":\"observe\""), "the rolled file is the trace, compressed");
    }

    @Test
    void doesNotWriteOutPacketsNothingUnderstood() throws IOException {
        Path file = dir.resolve("Agent1.jsonl");
        Trace trace = Trace.toFile(file, "Agent1");
        trace.observed(new Episode(0, 0, new Observation.Unrecognised(0, 39, "SHOW_STATUS_INFO", 9)));
        trace.observed(new Episode(1, 1, new Observation.MapEntered(1, 10000, 0)));
        trace.close();

        String written = Files.readString(file);
        assertFalse(written.contains("Unrecognised"));
        assertEquals(1, written.lines().count());
    }
}
