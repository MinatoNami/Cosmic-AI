package agents.trace;

import agents.Mind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shape of a deliberation line.
 *
 * The live page and the replay page both read this file rather than asking the daemon what
 * happened, so a field that quietly stops being written is a monitoring page that quietly
 * stops being true. Nothing else in the tests reads the trace back.
 */
class TraceTest {

    @TempDir
    Path directory;

    @Test
    void creditsTheDecisionToWhoeverMadeIt() throws IOException {
        String line = deliberationLine("reflex:fighter", "target gone");

        assertTrue(line.contains("\"by\":\"reflex:fighter\""), line);
        assertTrue(line.contains("\"fellBack\":\"target gone\""), line);
    }

    /** A decision nobody fell back on carries no explanation, and pays nothing for the field. */
    @Test
    void leavesOutTheReasonWhenNothingFellBack() throws IOException {
        String line = deliberationLine("llm:lmstudio:qwen", null);

        assertTrue(line.contains("\"by\":\"llm:lmstudio:qwen\""), line);
        assertFalse(line.contains("fellBack"), line);
    }

    private String deliberationLine(String by, String fellBackBecause) throws IOException {
        Path file = directory.resolve(by.replace(':', '-') + ".jsonl");
        Mind mind = new Mind("Test", Trace.toFile(file, "Test"));
        mind.decided(7, "hit what is in front of me", "Attack", Map.of("target", 9001),
                List.of(), List.of("door=0.42", "wander=0.05"), by, fellBackBecause, null);
        mind.flush();

        return Files.readAllLines(file).stream()
                .filter(l -> l.contains("\"kind\":\"deliberate\""))
                .findFirst().orElseThrow();
    }

    /**
     * Where the agent was standing, not only where it meant to go.
     *
     * Every question about movement was answered by inference before this, and the
     * inferences were wrong as often as right: the same destination repeated eleven times
     * read as "committed to a long walk" one day and "frozen against a wall" the next. The
     * intent's detail is the target; this is the agent.
     */
    @Test
    void recordsWhereTheAgentWasStanding() throws IOException {
        Path file = directory.resolve("standing.jsonl");
        Mind mind = new Mind("Test", Trace.toFile(file, "Test"));
        mind.decided(9, "walk to a way out: west00", "MoveTo", Map.of("x", -426, "y", 212),
                List.of(), List.of(), "reflex", null, new java.awt.Point(627, 155));
        mind.flush();

        String written = Files.readString(file);
        assertTrue(written.contains("\"at\":[627,155]"),
                "the act line should carry the position it acted from, got: " + written);
    }
}
