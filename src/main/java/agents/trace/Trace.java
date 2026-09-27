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

    private BufferedWriter writer;
    private final String agent;

    /**
     * Where this trace is written, and roughly how big it has got, so it can be rotated.
     *
     * Traces grew without limit - 20GB for one agent after two days - until nobody could read
     * them. Past {@link #ROTATE_AT} the file is compressed aside and a new one started, and
     * only the last {@link #KEEP_ROTATED} compressed files are kept.
     */
    private final Path path;
    private long bytes;
    private final long rotateAt;
    static final long ROTATE_AT = 256L * 1024 * 1024;
    static final int KEEP_ROTATED = 3;
    private final java.util.Set<String> labelled = new java.util.HashSet<>();
    private long nextDeliberationId;
    private long nextActionId;

    private Trace(BufferedWriter writer, String agent, Path path, long bytes, long rotateAt) {
        this.writer = writer;
        this.agent = agent;
        this.path = path;
        this.bytes = bytes;
        this.rotateAt = rotateAt;
    }

    public static Trace toFile(Path path, String agent) {
        return toFile(path, agent, ROTATE_AT);
    }

    /** With a rotation size of the caller's choosing, which is for tests. */
    static Trace toFile(Path path, String agent, long rotateAt) {
        try {
            Files.createDirectories(path.getParent());
            return new Trace(open(path), agent, path,
                    Files.isRegularFile(path) ? Files.size(path) : 0, rotateAt);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open trace file " + path, e);
        }
    }

    public void observed(Episode episode) {
        // A packet nothing understood says nothing a replay can use, and was a fifth of every
        // trace. It is still an episode; it is just not written out.
        if (episode.observation() instanceof agents.percept.Observation.Unrecognised) {
            return;
        }
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

    /**
     * A belief the agent woke up already holding.
     *
     * Written into this run's trace so a replay stands on its own. Flagged, because "knew
     * this before the run began" and "worked it out at tick 12" are different claims and a
     * reader should not have to guess which one a line is making.
     */
    public void carried(Belief belief) {
        label(belief.subject());
        label(belief.object());
        write("believe", belief.lastSeen(), Map.of(
                "id", belief.ref(),
                "triple", List.of(belief.subject(), belief.predicate(), belief.object()),
                "from", belief.supportedBy().stream().map(id -> "e" + id).toList(),
                "confidence", round(belief.confidence()),
                "provenance", belief.provenance().name().toLowerCase(),
                "corroborated", false,
                "carried", true));
    }

    /** Says that a run woke a saved mind, and how much of one. */
    public void resumed(long tick, int episodes, int beliefs) {
        write("resume", tick, Map.of("episodes", episodes, "beliefs", beliefs));
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
     * @param by the policy that actually chose
     * @param fellBackBecause why the policy that was asked did not choose, or null if it did.
     *                        Null fields are left out of the line, so an undivided run of
     *                        deliberations stays as compact as it was before anything fell back.
     * @return the id to quote as {@code because} on whatever action follows
     */
    public String deliberated(long tick, String goal, List<String> used, List<String> considered,
                              String by, String fellBackBecause) {
        String id = "d" + nextDeliberationId++;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("goal", goal);
        fields.put("used", used);
        fields.put("considered", considered);
        fields.put("by", by);
        fields.put("fellBack", fellBackBecause);
        write("deliberate", tick, fields);
        return id;
    }

    /**
     * @param at where the agent was standing when it acted, or null if it does not know yet
     *
     * The position is here because every question about movement has had to be answered by
     * inference, and the inferences were wrong as often as right. Was it walking to the door
     * or stuck against a wall? Did the climb work? Is a journey closing? The intent's own
     * detail says where the agent meant to go, never where it was, so "it emitted the same
     * destination eleven times" read as both "committed" and "frozen" on different days. One
     * pair of numbers per action settles all of it.
     */
    public String acted(long tick, String intent, Map<String, Object> detail, String because,
                        java.awt.Point at) {
        String id = "a" + nextActionId++;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("intent", intent);
        fields.put("detail", detail);
        fields.put("at", at == null ? null : List.of(at.x, at.y));
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

    private static BufferedWriter open(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Compresses the full trace aside as {@code <name>.1.gz}, shifting older ones along and
     * dropping the oldest, and starts an empty one.
     *
     * Done in line rather than on another thread: compressing 256MB takes a few seconds once
     * a day or so, and a second writer racing this one for the same file is not worth that.
     */
    private void rotate() {
        try {
            writer.close();
            Path oldest = rotated(KEEP_ROTATED);
            Files.deleteIfExists(oldest);
            for (int i = KEEP_ROTATED - 1; i >= 1; i--) {
                if (Files.exists(rotated(i))) {
                    Files.move(rotated(i), rotated(i + 1), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try (var in = Files.newInputStream(path);
                 var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(rotated(1)))) {
                in.transferTo(out);
            }
            Files.delete(path);
            log.info("Rotated the trace for {} to {}", agent, rotated(1).getFileName());
        } catch (IOException e) {
            log.warn("Could not rotate the trace for {}; carrying on in the same file", agent, e);
        }
        try {
            writer = open(path);
            bytes = Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not reopen the trace at " + path, e);
        }
    }

    private Path rotated(int generation) {
        String name = path.getFileName().toString().replaceFirst("\\.jsonl$", "");
        return path.resolveSibling(name + "." + generation + ".jsonl.gz");
    }

    private synchronized void write(String kind, long tick, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("t", tick);
        event.put("agent", agent);
        event.put("kind", kind);
        event.putAll(fields);

        try {
            String line = Json.object(event);
            writer.write(line);
            writer.newLine();
            bytes += line.length() + 1;
            if (bytes >= rotateAt) {
                rotate();
            }
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

    public synchronized void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            log.warn("Could not flush the trace for {}", agent, e);
        }
    }

    @Override
    public synchronized void close() {
        flush();
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("Could not close the trace for {}", agent, e);
        }
    }
}
