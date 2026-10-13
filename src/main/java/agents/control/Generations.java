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
 * replaced, so any two consolidations can be compared rather than only the latest.
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
     * condense is about to read.
     *
     * @return the archived file's name, or null if there was nothing to keep
     */
    public String archive(Path inheritance) {
        if (!Files.isRegularFile(inheritance)) {
            return null;
        }
        String name = inheritance.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String archived = name.substring(0, dot) + "." + STAMP.format(Instant.now())
                + name.substring(dot);
        try {
            Files.createDirectories(past);
            Files.copy(inheritance, past.resolve(archived), StandardCopyOption.REPLACE_EXISTING);
            return PAST_INHERITANCES + "/" + archived;
        } catch (IOException e) {
            // Losing the old copy is a pity; refusing to condense over it would be worse.
            log.warn("Could not archive {} before replacing it", inheritance, e);
            return null;
        }
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
