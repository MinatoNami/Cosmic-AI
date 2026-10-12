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

        // The world map, as a player has one. Off with AGENTS_WORLD_MAP=off, to see what agents
        // make of the world with nothing but their own walking to go on.
        if (!"off".equalsIgnoreCase(setting("AGENTS_WORLD_MAP", "on"))) {
            agents.world.KnownWorld.useAtlas(new agents.world.Atlas());
            log.info("Agents can see the world map");
        }
        if ("on".equalsIgnoreCase(setting("AGENTS_DOOR_ROUTING", "off"))) {
            agents.world.KnownWorld.useDoorRouting(true);
            log.info("Routes follow which doors can be walked to from each entrance");
        }
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
            autostart(population, Integer.parseInt(autostart), setting("AGENTS_POLICY", "reflex"));
        }

        // Nothing else to do on this thread: the API serves on its own, and the agents are
        // each on theirs.
        new CountDownLatch(1).await();
    }

    /**
     * Starts the agents, and keeps trying until the game server lets them in.
     *
     * A deploy restarts the server and this daemon together, and the server takes the better
     * part of a minute to load its data and open its ports. One attempt, made the moment this
     * came up, found nobody to log in to - and the agents stayed stopped until somebody
     * noticed, which twice in one morning was a quarter of an hour.
     */
    private static void autostart(Population population, int count, String policy) {
        for (int attempt = 1; attempt <= AUTOSTART_ATTEMPTS; attempt++) {
            if (population.isRunning()) {
                return;     // somebody started them by hand meanwhile
            }
            // The local model lives on somebody's machine across the network, and is not always
            // up. Waiting for it left the agents stopped after a reboot until somebody noticed;
            // better to play by reflex meanwhile and take the model on when it answers.
            String using = policy;
            if ("local".equalsIgnoreCase(policy) && !Population.localModelAnswers()) {
                using = "reflex";
                log.warn("No model answering at {} - starting on reflexes until there is",
                        Population.localModelUrl());
            }
            log.info("Autostarting {} agent(s), policy {} (attempt {})", count, using, attempt);
            try {
                if (!population.start(count, using).isEmpty()) {
                    if (!using.equalsIgnoreCase(policy)) {
                        watchForTheModel(population, count, policy);
                    }
                    return;
                }
                population.stop();      // nobody got in; clear the way for the next try
            } catch (RuntimeException e) {
                log.warn("Autostart attempt {} failed: {}", attempt, e.getMessage());
            }
            try {
                Thread.sleep(AUTOSTART_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.error("Gave up autostarting after {} attempts; start them by hand", AUTOSTART_ATTEMPTS);
    }

    /**
     * Moves agents started on reflexes over to the local model once it answers.
     *
     * Restarting them is how: stopping writes every mind down, so they come back knowing
     * everything they knew, with the model deciding. Left alone if anybody has stopped them
     * or chosen a policy by hand in the meantime.
     */
    private static void watchForTheModel(Population population, int count, String policy) {
        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(MODEL_CHECK_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!population.isRunning() || !"reflex".equalsIgnoreCase(population.policy())) {
                    return;
                }
                if (Population.localModelAnswers()) {
                    log.info("The model at {} is answering - restarting the agents on it",
                            Population.localModelUrl());
                    population.stop();
                    try {
                        if (!population.start(count, policy).isEmpty()) {
                            return;
                        }
                    } catch (RuntimeException e) {
                        log.warn("Could not start on the model after all: {}", e.getMessage());
                    }
                    population.stop();
                    try {
                        population.start(count, "reflex");
                    } catch (RuntimeException e) {
                        log.error("Could not restart the agents on reflexes either; start them by hand", e);
                        return;
                    }
                }
            }
        }, "model-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static final long MODEL_CHECK_MILLIS = 120_000;

    /** Ten minutes of trying, which is far longer than a server takes to come up. */
    private static final int AUTOSTART_ATTEMPTS = 40;
    private static final long AUTOSTART_WAIT_MILLIS = 15_000;

    private static String setting(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
