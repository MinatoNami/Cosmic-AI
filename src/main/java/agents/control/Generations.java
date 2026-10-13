package agents.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The line's own history: every condense and every reset, one JSON object per line.
 *
 * Until this existed the only record of a generation turning over was a backup folder
 * somebody remembered to copy, and the counts a condense reported went to whoever made the
 * request and nowhere else. Whether a condense had run before a reset could only be guessed
 * from file dates. Append-only for the same reason the traces are: it survives the process
 * dying halfway through, and nothing ever needs to rewrite history.
 *
 * Each inheritance a condense replaces is kept beside the ledger, named by when it was
 * made, so any two consolidations can be compared rather than only the latest.
 */
public class Generations {
    private static final Logger log = LoggerFactory.getLogger(Generations.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Filesystem-safe and sorts in time order. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public static final String LEDGER = "generations.jsonl";
    /**
     * Outside the minds directory on purpose: anything ending .mind in there is read as an
     * agent's mind, and a past inheritance condensed back in as if it were one would count
     * its beliefs twice.
     */
    public static final String PAST_INHERITANCES = "inheritance";

    /** A saved mind, as it stood when something was done to it. */
    public record MindFile(String file, long bytes) {
        static MindFile of(Path path) {
            long bytes;
            try {
                bytes = Files.size(path);
            } catch (IOException e) {
                bytes = -1;
            }
            return new MindFile(path.getFileName().toString(), bytes);
        }
    }

    private final Path ledger;
    private final Path past;

    public Generations(Path dataDirectory) {
        this.ledger = dataDirectory.resolve(LEDGER);
        this.past = dataDirectory.resolve(PAST_INHERITANCES);
    }

    public Path file() {
        return ledger;
    }

    /**
     * Copies the inheritance about to be replaced into the archive.
     *
     * A copy rather than a move, because the standing inheritance is one of the sources the
     * condense is about to read. Named by when it was made - the condense that wrote it -
     * rather than when it was replaced, because "the inheritance of the 28th" is what
     * somebody comparing generations is looking for.
     *
     * @return the archived file's path relative to the data directory, or null if there was
     *         nothing to keep
     */
    public String archive(Path inheritance) {
        if (!Files.isRegularFile(inheritance)) {
            return null;
        }
        try {
            String stamp = STAMP.format(Files.getLastModifiedTime(inheritance).toInstant());
            String archived = "inherited." + stamp + ".mind";
            // Two made within the same second - a rebuild's steps can be - must not overwrite
            // one another. The same one archived twice may.
            for (int n = 2; Files.exists(past.resolve(archived))
                    && Files.size(past.resolve(archived)) != Files.size(inheritance); n++) {
                archived = "inherited." + stamp + "-" + n + ".mind";
            }
            Files.createDirectories(past);
            // With its file time, which is when it was made: the page reads that back as the
            // snapshot's date, and a copy stamped with today labelled an old inheritance as new.
            Files.copy(inheritance, past.resolve(archived), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            return PAST_INHERITANCES + "/" + archived;
        } catch (IOException e) {
            // Losing the old copy is a pity; refusing to condense over it would be worse.
            log.warn("Could not archive {} before replacing it", inheritance, e);
            return null;
        }
    }

    /** An inheritance that can be compared with another: a past one, or the one standing. */
    public record Snapshot(String name, String madeAt, long bytes) {
    }

    public static final String STANDING = "current";

    /** Every inheritance there is to compare, oldest first, the standing one last. */
    public List<Snapshot> snapshots(Path standing) {
        List<Snapshot> all = new ArrayList<>();
        if (Files.isDirectory(past)) {
            try (var entries = Files.list(past)) {
                for (Path p : entries.sorted().toList()) {
                    if (ARCHIVED.matcher(p.getFileName().toString()).matches()) {
                        all.add(snapshotOf(p.getFileName().toString(), p));
                    }
                }
            } catch (IOException e) {
                log.warn("Could not list {}", past, e);
            }
        }
        if (Files.isRegularFile(standing)) {
            all.add(snapshotOf(STANDING, standing));
        }
        return all;
    }

    private static Snapshot snapshotOf(String name, Path file) {
        try {
            return new Snapshot(name, Files.getLastModifiedTime(file).toInstant().toString(),
                    Files.size(file));
        } catch (IOException e) {
            return new Snapshot(name, null, -1);
        }
    }

    private static final java.util.regex.Pattern ARCHIVED =
            java.util.regex.Pattern.compile("inherited\\.\\d{8}T\\d{6}Z(-\\d{1,3})?\\.mind");

    /**
     * The file behind a snapshot's name, or null for a name that is not one.
     *
     * Names come from a query string, so only the exact archive pattern is accepted - never
     * a path - and nothing outside the archive can be read through this.
     */
    public Path resolve(String name, Path standing) {
        if (STANDING.equals(name)) {
            return Files.isRegularFile(standing) ? standing : null;
        }
        if (name == null || !ARCHIVED.matcher(name).matches()) {
            return null;
        }
        Path file = past.resolve(name);
        return Files.isRegularFile(file) ? file : null;
    }

    /**
     * Appends one event.
     *
     * Never throws: the ledger is a record of what happened, and a full disk should not be
     * what stops it happening.
     */
    public synchronized void record(String event, Map<String, Object> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", Instant.now().toString());
        entry.put("event", event);
        entry.putAll(details);
        try {
            Files.createDirectories(ledger.getParent());
            Files.writeString(ledger, JSON.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not record a {} in {}", event, ledger, e);
        }
    }

    /** Every event so far, oldest first. A line that will not parse is skipped, not fatal. */
    public synchronized List<Map<String, Object>> read() {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (!Files.isRegularFile(ledger)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    entries.add(JSON.readValue(line, new TypeReference<Map<String, Object>>() { }));
                } catch (IOException torn) {
                    log.warn("Skipping an unreadable line in {}", ledger);
                }
            }
        } catch (IOException e) {
            log.warn("Could not read {}", ledger, e);
        }
        return entries;
    }
}
