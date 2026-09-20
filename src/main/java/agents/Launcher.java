package agents;

import agents.memory.Belief;
import agents.mind.ClaudeOracle;
import agents.mind.Disposition;
import agents.mind.LlmPolicy;
import agents.mind.LmStudioOracle;
import agents.mind.Oracle;
import agents.mind.Policy;
import agents.mind.ReflexPolicy;
import agents.net.LoginFlow;
import agents.net.LoginFlow.Credentials;
import agents.net.LoginFlow.InWorld;
import agents.trace.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Runs a population of agents against a Cosmic server.
 *
 * <pre>
 *   java -cp ... agents.Launcher [host] [port] [count] [minutes] [policy]
 * </pre>
 *
 * Policy is {@code reflex} (the default), {@code llm} for Claude, or {@code local} for a
 * model served by LM Studio. Claude needs a key in the environment or in .env; LM Studio
 * needs to be running, and the model name is discovered from the server.
 *
 * Each agent gets its own thread, its own memory and its own trace file. They share nothing
 * but the world.
 */
public class Launcher {
    private static final Logger log = LoggerFactory.getLogger(Launcher.class);

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8484;
    private static final int WORLD = 0;

    /** At a 600ms tick this is roughly one question every 18 seconds. */
    private static final int LOCAL_DELIBERATE_EVERY = 30;
    private static final int CHANNEL = 1;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        Duration runFor = Duration.ofMinutes(args.length > 3 ? Long.parseLong(args[3]) : 1);
        String policyName = args.length > 4 ? args[4].toLowerCase() : "reflex";

        Oracle oracle = null;
        if (policyName.equals("llm")) {
            if (!ClaudeOracle.credentialsAvailable()) {
                log.error("No ANTHROPIC_API_KEY in the environment or in .env. Every decision "
                        + "would fall back to reflexes, which proves nothing - refusing to "
                        + "start. Add the key to .env (it is gitignored), then try again.");
                return;
            }
            oracle = new ClaudeOracle();
        } else if (policyName.equals("local")) {
            String url = System.getProperty("lmstudio.url", "http://localhost:1234/v1/chat/completions");
            String model = LmStudioOracle.discoverModel(url);
            if (model == null) {
                log.error("Nothing answering at {} - is LM Studio running with a model loaded? "
                        + "Refusing to start, since every decision would fall back to reflexes.", url);
                return;
            }
            log.info("Using the model LM Studio has loaded: {}", model);
            oracle = new LmStudioOracle(url, model);
        }

        Path traceDir = Path.of("target", "traces", String.valueOf(System.currentTimeMillis()));
        log.info("Starting {} agent(s) against {}:{} for {}, policy {}",
                count, host, port, runFor, policyName);

        List<Agent> agents = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String name = "Agent" + i;
            try {
                Random random = new Random(name.hashCode());
                Disposition disposition = Disposition.forAgent(i);
                LoginFlow flow = new LoginFlow(host, port, WORLD, CHANNEL, random);
                InWorld connection = flow.enterWorld(new Credentials(name.toLowerCase(), "agentpass"), name);

                Mind mind = new Mind(name, Trace.toFile(traceDir.resolve(name + ".jsonl"), name));
                Policy reflex = new ReflexPolicy(random, disposition);
                // A local model takes about fifteen seconds to answer, so asking every eighth
                // decision would queue up behind itself. Ask about as often as it can reply.
                Policy policy = oracle == null ? reflex
                        : new LlmPolicy(oracle, reflex, LOCAL_DELIBERATE_EVERY);
                Agent agent = new Agent(connection, mind, policy, disposition);

                agents.add(agent);
                Thread thread = new Thread(agent, name);
                thread.start();
                threads.add(thread);
            } catch (Exception e) {
                log.error("{} failed to enter the world", name, e);
            }
        }

        if (agents.isEmpty()) {
            log.error("No agents made it into the world");
            return;
        }

        try {
            TimeUnit.MILLISECONDS.sleep(runFor.toMillis());
        } finally {
            agents.forEach(Agent::stop);
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            }
            agents.forEach(Launcher::report);
            agents.forEach(agent -> agent.mind().close());
            log.info("Traces written to {}", traceDir.toAbsolutePath());
        }
    }

    private static void report(Agent agent) {
        Mind mind = agent.mind();
        log.info("[{}] map {}, level {}, hp {}/{} | {} episodes, {} beliefs ({} live, {} revised)",
                mind.name(), agent.world().mapId(), agent.world().level(),
                agent.world().hp(), agent.world().maxHp(),
                mind.episodic().size(), mind.semantic().size(),
                mind.semantic().liveBeliefs().size(),
                mind.semantic().all().size() - mind.semantic().liveBeliefs().size());

        mind.semantic().liveBeliefs().stream()
                .sorted(Comparator.comparingDouble(Belief::confidence).reversed())
                .limit(10)
                .forEach(b -> log.info("[{}]    {} ({}, from {} episode(s))", mind.name(),
                        readable(b), String.format("%.2f", b.confidence()), b.supportedBy().size()));
    }

    /**
     * The agent's own sentence with names filled in, for the person reading the console.
     * The agent itself only ever holds the ids.
     */
    private static String readable(Belief belief) {
        return name(belief.subject()) + " " + belief.predicate() + " " + name(belief.object());
    }

    private static String name(String ref) {
        String label = agents.trace.Labels.forRef(ref);
        return label == null || label.isBlank() ? ref : ref + " (" + label + ")";
    }
}
