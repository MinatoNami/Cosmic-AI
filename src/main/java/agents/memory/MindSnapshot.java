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
 * Episodes are written in full rather than only the ones a belief cites. Both episode and
 * belief ids are positions in a list, so a sparse restore would not fail - it would quietly
 * point a belief's evidence at somebody else's episode. That makes the file grow with the
 * agent's whole life, which is the same unbounded growth {@link EpisodicMemory} already
 * carries and the same place it will have to be solved.
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
            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                out.write(join("mind", String.valueOf(FORMAT), text(agent), String.valueOf(tick)));
                out.newLine();

                for (Episode episode : episodic.all()) {
                    out.write(join("e", String.valueOf(episode.id()), String.valueOf(episode.tick()),
                            text(episode.observation().getClass().getSimpleName()),
                            text(episode.observation().toString())));
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
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
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
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(SEP, -1);
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
                        episodic.restore(Long.parseLong(f[2]), plain(f[3]), plain(f[4]));
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
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the mind at " + path, e);
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
