package agents.trace;

import agents.memory.Belief;
import agents.memory.Episode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The causal record of a run, one JSON object per line.
 *
 * Four kinds of event and two link fields are enough to answer both questions worth asking.
 * Forwards: this observation produced this belief, which justified this decision, which
 * became this action. Backwards, which is the one you actually want at three in the morning:
 * why did it do that? Follow {@code because} to the deliberation, {@code used} to the
 * beliefs, {@code from} to the episodes, and you are back at raw packets.
 *
 * JSONL rather than a database because a run should be a file you can hand to someone, and
 * because an append-only log survives the process dying mid-thought.
 */
public class Trace implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Trace.class);

    private final BufferedWriter writer;
    private final String agent;
    private final java.util.Set<String> labelled = new java.util.HashSet<>();
    private long nextDeliberationId;
    private long nextActionId;

    private Trace(BufferedWriter writer, String agent) {
        this.writer = writer;
        this.agent = agent;
    }

    public static Trace toFile(Path path, String agent) {
        try {
            Files.createDirectories(path.getParent());
            BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new Trace(writer, agent);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open trace file " + path, e);
        }
    }

    public void observed(Episode episode) {
        write("observe", episode.tick(), Map.of(
                "id", episode.ref(),
                "obs", describe(episode)));
    }

    /**
     * @param corroborated true when this strengthened a belief rather than creating one, which
     *                     is worth distinguishing in a replay - a confidence climbing over
     *                     time reads very differently from a run of separate conclusions
     */
    public void believed(Belief belief, boolean corroborated) {
        label(belief.subject());
        label(belief.object());
        write("believe", belief.lastSeen(), Map.of(
                "id", belief.ref(),
                "triple", List.of(belief.subject(), belief.predicate(), belief.object()),
                "from", belief.supportedBy().stream().map(id -> "e" + id).toList(),
                "confidence", round(belief.confidence()),
                "provenance", belief.provenance().name().toLowerCase(),
                "corroborated", corroborated));
    }

    /** A belief the agent stopped holding, and what replaced it. */
    public void revised(Belief invalidated, Belief replacement) {
        write("revise", invalidated.invalidatedAt(), Map.of(
                "id", invalidated.ref(),
                "triple", List.of(invalidated.subject(), invalidated.predicate(), invalidated.object()),
                "supersededBy", replacement.ref(),
                "heldFor", invalidated.invalidatedAt() - invalidated.firstSeen()));
    }

    /**
     * @param used beliefs that informed the choice, by ref
     * @return the id to quote as {@code because} on whatever action follows
     */
    public String deliberated(long tick, String goal, List<String> used, List<String> considered) {
        String id = "d" + nextDeliberationId++;
        write("deliberate", tick, Map.of(
                "id", id,
                "goal", goal,
                "used", used,
                "considered", considered));
        return id;
    }

    public String acted(long tick, String intent, Map<String, Object> detail, String because) {
        String id = "a" + nextActionId++;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("intent", intent);
        fields.put("detail", detail);
        fields.put("because", because);
        write("act", tick, fields);
        return id;
    }

    /**
     * Emits a human name for an id, once per run.
     *
     * Display only, and written by the instrumentation rather than by the agent: the agent's
     * own memory never holds these, because "Blue Snail" gives away most of what it is
     * supposed to work out for itself. See {@link Labels}.
     */
    private void label(String ref) {
        if (ref == null || !labelled.add(ref)) {
            return;
        }
        String name = Labels.forRef(ref);
        if (name != null && !name.isBlank()) {
            write("label", 0, Map.of("ref", ref, "text", name));
        }
    }

    private void write(String kind, long tick, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("t", tick);
        event.put("agent", agent);
        event.put("kind", kind);
        event.putAll(fields);

        try {
            writer.write(Json.object(event));
            writer.newLine();
        } catch (IOException e) {
            // A broken trace must not take the agent down with it: losing the record of a run
            // is bad, losing the run is worse.
            log.warn("Could not write to the trace for {}", agent, e);
        }
    }

    /** Records carry their own field names, which is exactly what a replay wants to show. */
    private static Map<String, Object> describe(Episode episode) {
        Object observation = episode.observation();
        return Map.of(
                "type", observation.getClass().getSimpleName(),
                "detail", observation.toString());
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    public void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            log.warn("Could not flush the trace for {}", agent, e);
        }
    }

    @Override
    public void close() {
        flush();
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("Could not close the trace for {}", agent, e);
        }
    }
}
