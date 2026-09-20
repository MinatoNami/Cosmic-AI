package agents;

import agents.control.CharacterReset;
import agents.control.ControlApi;
import agents.control.Population;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Runs agents until told to stop, and puts a door on the room.
 *
 * The difference from {@link Launcher} is a run's lifetime. A launcher run is an experiment:
 * a fixed number of minutes, a report, an exit. This is a resident process you can leave up,
 * start and stop agents in, watch, and reset - which is what it takes to log in with a real
 * client and find them there.
 *
 * <pre>
 *   java -cp ... agents.Daemon [gameHost] [gamePort] [apiPort]
 * </pre>
 *
 * Everything else comes from the environment, because this runs in a container where flags
 * are awkward and environment is not: {@code AGENTS_DATA} for where minds and traces live,
 * {@code AGENTS_UI} for the page, {@code LMSTUDIO_URL} for the local model, and the same
 * {@code DB_*} settings the server reads.
 */
public class Daemon {
    private static final Logger log = LoggerFactory.getLogger(Daemon.class);

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_GAME_PORT = 8484;
    private static final int DEFAULT_API_PORT = 8090;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : setting("GAME_HOST", DEFAULT_HOST);
        int gamePort = args.length > 1 ? Integer.parseInt(args[1])
                : Integer.parseInt(setting("GAME_PORT", String.valueOf(DEFAULT_GAME_PORT)));
        int apiPort = args.length > 2 ? Integer.parseInt(args[2])
                : Integer.parseInt(setting("AGENTS_API_PORT", String.valueOf(DEFAULT_API_PORT)));

        Path data = Path.of(setting("AGENTS_DATA", "target/agents"));
        Path ui = Path.of(setting("AGENTS_UI", "viz"));

        Population population = new Population(host, gamePort, data);
        ControlApi api = new ControlApi(population, CharacterReset.fromEnvironment(), ui, apiPort);

        // Stopping writes every mind down, so a container stop is not a lobotomy.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down - saving minds");
            population.stop();
            api.stop();
        }, "daemon-shutdown"));

        api.start();
        log.info("Agents daemon up. Game server {}:{}, control on http://0.0.0.0:{}, data in {}",
                host, gamePort, apiPort, data.toAbsolutePath());
        log.info("Local model would be asked at {}", Population.localModelUrl());

        String autostart = setting("AGENTS_AUTOSTART", "");
        if (!autostart.isBlank()) {
            log.info("Autostarting {} agent(s), policy {}", autostart, setting("AGENTS_POLICY", "reflex"));
            try {
                population.start(Integer.parseInt(autostart), setting("AGENTS_POLICY", "reflex"));
            } catch (RuntimeException e) {
                log.error("Autostart failed, the daemon is still up and you can start them by hand", e);
            }
        }

        // Nothing else to do on this thread: the API serves on its own, and the agents are
        // each on theirs.
        new CountDownLatch(1).await();
    }

    private static String setting(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
