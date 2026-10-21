package agents.memory;

import agents.memory.Belief.Provenance;
import org.apache.commons.text.StringEscapeUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * An agent's mind on disk, so the next run starts where the last one stopped.
 *
 * One line per record, tab separated, free text escaped as JSON would escape it - which
 * means no field can contain a tab or a newline, so a line is always a record and the file
 * survives being read by anything that can split a string. A JSON document would need a
 * parser the project does not have and does not want to add for this.
 *
 * Episodes are written with their ids and restored under them, so a mind that has let go of
 * old episodes ({@link EpisodicMemory#forgetAllBut}) saves and loads with gaps rather than
 * with every later episode shifted onto an earlier one's id. Minds saved before that wrote
 * every episode in order, and read back exactly as they did.
 *
 * Writes go to a sibling temporary file and are moved into place, so a process dying
 * mid-save leaves the previous mind intact rather than half of two.
 */
public final class MindSnapshot {
    private static final int FORMAT = 1;
    private static final String SEP = "\t";
    private static final String NONE = "-";

    private MindSnapshot() {
    }

    /** What a restore put back, for the caller to report and to trace. */
    public record Restored(String agent, long tick, int episodes, int beliefs) {
    }

    public static void save(Path path, String agent, long tick,
                            EpisodicMemory episodic, SemanticMemory semantic) {
        Path temporary = path.resolveSibling(path.getFileName() + ".writing");
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(temporary.toFile());
                 BufferedWriter out = new BufferedWriter(new java.io.OutputStreamWriter(file, StandardCharsets.UTF_8))) {
                out.write(join("mind", String.valueOf(FORMAT), text(agent), String.valueOf(tick)));
                out.newLine();

                for (Episode episode : episodic.all()) {
                    // Something recalled is written as what it was, not as a recollection of
                    // it: otherwise every save wraps it once more, and a dialogue line five
                    // restarts old was five "Recalled[...]" deep with its words buried inside.
                    String type = episode.observation() instanceof agents.percept.Observation.Recalled recalled
                            ? recalled.type() : episode.observation().getClass().getSimpleName();
                    String detail = episode.observation() instanceof agents.percept.Observation.Recalled recalled
                            ? recalled.detail() : episode.observation().toString();
                    out.write(join("e", String.valueOf(episode.id()), String.valueOf(episode.tick()),
                            text(type), text(detail)));
                    out.newLine();
                }
                for (Belief belief : semantic.all()) {
                    out.write(join("b", String.valueOf(belief.id()),
                            text(belief.subject()), text(belief.predicate()), text(belief.object()),
                            String.valueOf(belief.confidence()), belief.provenance().name(),
                            String.valueOf(belief.firstSeen()), String.valueOf(belief.lastSeen()),
                            belief.invalidatedAt() == null ? NONE : String.valueOf(belief.invalidatedAt()),
                            belief.supersededBy() == null ? NONE : String.valueOf(belief.supersededBy()),
                            belief.supportedBy().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")));
                    out.newLine();
                }
                // On the disk before it takes the old mind's place. Renamed with its words
                // still in memory, a mind survived the server rebooting as a name with a torn
                // file under it: Agent1's and Agent2's were cut off at 20 MiB, and neither woke.
                out.flush();
                file.getFD().sync();
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the mind to " + path, e);
        }
    }

    /**
     * Reads a mind back into empty memories.
     *
     * @return what was restored, or null when there is no file to restore from - a first run
     *         is not an error
     */
    public static Restored load(Path path, EpisodicMemory episodic, SemanticMemory semantic) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        if (episodic.size() != 0 || semantic.size() != 0) {
            throw new IllegalStateException("A mind restores into empty memories, not over a live one");
        }
        String agent = "";
        long tick = 0;
        int episodes = 0;
        int beliefs = 0;
        int torn = 0;
        try {
            // Decoded forgivingly: a file cut off in the middle of a character is still worth
            // every whole line before the cut.
            String all = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString();
            for (String line : all.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(SEP, -1);
                try {
                switch (f[0]) {
                    case "mind" -> {
                        int format = Integer.parseInt(f[1]);
                        if (format != FORMAT) {
                            throw new IllegalStateException(
                                    "Mind at " + path + " is format " + format + ", this build reads " + FORMAT);
                        }
                        agent = plain(f[2]);
                        tick = Long.parseLong(f[3]);
                    }
                    case "e" -> {
                        episodic.restore(Long.parseLong(f[1]), Long.parseLong(f[2]), plain(f[3]), plain(f[4]));
                        episodes++;
                    }
                    case "b" -> {
                        semantic.restore(new Belief(Long.parseLong(f[1]),
                                plain(f[2]), plain(f[3]), plain(f[4]),
                                Double.parseDouble(f[5]), Provenance.valueOf(f[6]),
                                episodeIds(f[11]),
                                Long.parseLong(f[7]), Long.parseLong(f[8]),
                                f[9].equals(NONE) ? null : Long.parseLong(f[9]),
                                f[10].equals(NONE) ? null : Long.parseLong(f[10])));
                        beliefs++;
                    }
                    default -> {
                        // A record kind from a later format. Skipping beats refusing to wake up.
                    }
                }
                } catch (RuntimeException brokenLine) {
                    if (brokenLine instanceof IllegalStateException && f[0].equals("mind")) {
                        throw brokenLine;       // a format this build does not read is not a torn line
                    }
                    // A line that does not read - the last one, cut off when the machine went
                    // down mid-save - is left out rather than costing the whole mind.
                    torn++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the mind at " + path, e);
        }
        if (torn > 0) {
            org.slf4j.LoggerFactory.getLogger(MindSnapshot.class)
                    .warn("Mind at {} had {} line(s) that did not read; restored the rest", path, torn);
        }
        return new Restored(agent, tick, episodes, beliefs);
    }

    private static List<Long> episodeIds(String field) {
        List<Long> ids = new ArrayList<>();
        for (String id : field.split(",")) {
            if (!id.isBlank()) {
                ids.add(Long.parseLong(id));
            }
        }
        return ids;
    }

    private static String join(String... fields) {
        return String.join(SEP, fields);
    }

    /** Escaped so that a field can never contain the separator or end the line. */
    private static String text(String value) {
        return StringEscapeUtils.escapeJson(value == null ? "" : value);
    }

    private static String plain(String value) {
        return StringEscapeUtils.unescapeJson(value);
    }
}
