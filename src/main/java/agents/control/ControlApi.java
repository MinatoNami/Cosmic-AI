package agents.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The daemon's control surface: start, stop, reset, and a live view of what is happening.
 *
 * The JDK's own HTTP server rather than a framework, because this serves four endpoints and a
 * page, and a game server should not grow a web stack to do it.
 *
 * Deliberately without CORS headers. These endpoints stop agents and wipe minds, the page
 * that drives them is served from here, and same-origin is the whole of the protection a tool
 * on a private tailnet needs. Anything that wants wider access should say so on purpose.
 */
public class ControlApi {
    private static final Logger log = LoggerFactory.getLogger(ControlApi.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * How much trace to hand over in one poll. A following page asks about once a second and
     * an agent writes a few kilobytes in that time; this is the ceiling for a page that has
     * fallen behind, so it catches up over several polls instead of in one enormous response.
     */
    private static final int MOST_TRACE_PER_POLL = 512 * 1024;

    private final Population population;
    private final CharacterReset characterReset;
    private final Path ui;
    private final HttpServer http;

    public ControlApi(Population population, CharacterReset characterReset, Path ui, int port)
            throws IOException {
        this.population = population;
        this.characterReset = characterReset;
        this.ui = ui;
        this.http = HttpServer.create(new InetSocketAddress(port), 0);

        http.createContext("/api/status", this::status);
        http.createContext("/api/start", this::start);
        http.createContext("/api/stop", this::stop);
        http.createContext("/api/reset", this::reset);
        http.createContext("/api/condense", this::condense);
        http.createContext("/api/beliefs/", this::beliefs);
        http.createContext("/api/trace/", this::trace);
        http.createContext("/", this::page);
        http.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        http.start();
    }

    public void stop() {
        http.stop(0);
    }

    private void status(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", population.isRunning());
        body.put("policy", population.policy());
        body.put("startedAt", population.startedAt());
        body.put("agents", population.status());
        send(exchange, 200, body);
    }

    private void start(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "POST"));
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        int count = Integer.parseInt(query.getOrDefault("count", "3"));
        String policy = query.getOrDefault("policy", "reflex");
        try {
            List<String> awake = population.start(count, policy);
            send(exchange, awake.isEmpty() ? 502 : 200, Map.of(
                    "started", awake,
                    "policy", policy,
                    "note", awake.isEmpty() ? "nobody made it into the world - is the server up?" : ""));
        } catch (RuntimeException e) {
            send(exchange, 409, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void stop(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "POST"));
            return;
        }
        population.stop();
        send(exchange, 200, Map.of("running", false));
    }

    /**
     * Back to nothing: no levels, no items, no memories.
     *
     * Stops the agents first rather than refusing, because "reset" from a page means reset,
     * and a caller that has to stop, wait, then reset has been given a chore rather than a
     * button.
     */
    /**
     * Folds what this generation learned into the inheritance the next one is born with.
     *
     * Separate from reset on purpose. Condensing is a decision about the line; resetting is
     * a decision about the bodies. Doing them in one call would mean you could never look at
     * what was about to be inherited before the agents it came from were gone.
     */
    private void condense(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "POST"));
            return;
        }
        try {
            agents.memory.Inheritance.Merged merged = population.condense();
            send(exchange, 200, Map.of(
                    "mindsRead", merged.minds(),
                    "beliefsRead", merged.beliefsRead(),
                    "inherited", merged.beliefsKept(),
                    "agreedOn", merged.agreed(),
                    "note", merged.minds() == 0
                            ? "no saved minds to condense - have the agents run and stopped?"
                            : "the next generation will be born knowing " + merged.beliefsKept()
                              + " things"));
        } catch (RuntimeException e) {
            log.error("Condense failed", e);
            send(exchange, 409, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void reset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "POST"));
            return;
        }
        try {
            population.stop();
            int minds = population.forgetMinds();
            CharacterReset.Result characters = characterReset.wipe();
            send(exchange, 200, Map.of(
                    "mindsForgotten", minds,
                    "charactersDeleted", characters.characters(),
                    "rowsCleared", characters.ownedRows(),
                    "note", characters.note()));
        } catch (RuntimeException e) {
            log.error("Reset failed", e);
            send(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void beliefs(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring("/api/beliefs/".length());
        send(exchange, 200, Map.of("agent", name, "beliefs", population.beliefs(name)));
    }

    /**
     * Whatever an agent has written to its trace since the caller last asked.
     *
     * Byte offsets rather than line numbers, because the file only ever grows and an offset
     * makes the next read a seek rather than a scan. The body is the raw JSONL, which is what
     * the page wants anyway - re-parsing it here to re-serialise it would be work done twice.
     */
    private void trace(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring("/api/trace/".length());
        if (!name.matches("[A-Za-z0-9_-]{1,32}")) {
            send(exchange, 400, Map.of("error", "not an agent name"));
            return;
        }
        Path file = population.traceDirectory().resolve(name + ".jsonl");
        if (!Files.isRegularFile(file)) {
            // Nothing written yet is not an error for something following along - an agent
            // that has just woken up has a trace coming. Answering 404 here cost two
            // measurements: the caller took the tail offset from a reply that carried no
            // offset header, then asked for it back and got a parse failure, which reads
            // exactly like an agent that did nothing at all.
            emptyTrace(exchange, 0);
            return;
        }
        long from = offsetIn(exchange.getRequestURI());
        // from=-1 means "whatever happens from now on", which is what a page that has just
        // been opened wants: replaying a trace that has been growing for hours to find out
        // what is happening this second is work nobody asked for.
        if (from < 0) {
            from = Files.size(file);
        }
        byte[] chunk;
        long next;
        try (RandomAccessFile reading = new RandomAccessFile(file.toFile(), "r")) {
            long length = reading.length();
            // A file that shrank was reset underneath us; start it again from the top.
            if (from > length) {
                from = 0;
            }
            int wanted = (int) Math.min(MOST_TRACE_PER_POLL, length - from);
            chunk = new byte[Math.max(0, wanted)];
            if (wanted > 0) {
                reading.seek(from);
                reading.readFully(chunk);
            }
            next = from + Math.max(0, wanted);
        }
        // Never hand back half a line: the page parses line by line and a torn one is a
        // parse error it cannot recover from without knowing what was dropped.
        int lastNewline = lastIndexOf(chunk, (byte) '\n');
        if (lastNewline >= 0 && lastNewline + 1 < chunk.length) {
            next = from + lastNewline + 1;
            byte[] whole = new byte[lastNewline + 1];
            System.arraycopy(chunk, 0, whole, 0, whole.length);
            chunk = whole;
        } else if (lastNewline < 0) {
            next = from;
            chunk = new byte[0];
        }
        exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().add("X-Next-Offset", String.valueOf(next));
        exchange.sendResponseHeaders(200, chunk.length == 0 ? -1 : chunk.length);
        if (chunk.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(chunk);
            }
        } else {
            exchange.close();
        }
    }

    /**
     * The offset to read from, forgivingly.
     *
     * A missing, blank or unreadable offset means the beginning. It used to throw, which the
     * JDK's server turns into an empty 500 - indistinguishable, to a caller counting events,
     * from a quiet agent.
     */
    private static long offsetIn(URI uri) {
        String asked = query(uri).get("from");
        if (asked == null || asked.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(asked.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Nothing to hand over, and where to ask from next time. */
    private static void emptyTrace(HttpExchange exchange, long next) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().add("X-Next-Offset", String.valueOf(next));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    /** The monitoring page and whatever it asks for beside it. */
    private void page(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String wanted = path.equals("/") ? "live.html" : path.substring(1);
        Path file = ui.resolve(wanted).normalize();
        if (!file.startsWith(ui.normalize()) || !Files.isRegularFile(file)) {
            send(exchange, 404, Map.of("error", "no such page"));
            return;
        }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().add("Content-Type", contentType(wanted));
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static int lastIndexOf(byte[] data, byte wanted) {
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == wanted) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                values.put(pair.substring(0, equals),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] json = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(json);
        }
    }
}
