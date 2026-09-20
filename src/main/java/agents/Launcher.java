package agents;

import agents.net.LoginFlow;
import agents.net.LoginFlow.Credentials;
import agents.net.LoginFlow.InWorld;
import agents.percept.Observation;
import agents.percept.Perceiver;
import agents.protocol.ClientPackets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs a population of agents against a Cosmic server.
 *
 * At this stage each agent only proves the connection works: it enters the world, greets
 * anyone listening, and shuffles about. Perception, memory and cognition arrive next; see
 * {@code docs/ai-agents/roadmap.md}.
 *
 * <pre>
 *   java -cp ... agents.Launcher [host] [port] [count]
 * </pre>
 */
public class Launcher {
    private static final Logger log = LoggerFactory.getLogger(Launcher.class);

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8484;
    private static final int WORLD = 0;
    private static final int CHANNEL = 1;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 1;

        log.info("Starting {} agent(s) against {}:{}", count, host, port);

        List<InWorld> live = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "Agent" + i;
            try {
                LoginFlow flow = new LoginFlow(host, port, WORLD, CHANNEL, new Random(name.hashCode()));
                live.add(flow.enterWorld(new Credentials(name.toLowerCase(), "agentpass"), name));
            } catch (Exception e) {
                log.error("{} failed to enter the world", name, e);
            }
        }

        if (live.isEmpty()) {
            log.error("No agents made it into the world");
            return;
        }

        try {
            wanderFor(live, Duration.ofSeconds(30));
        } finally {
            live.forEach(agent -> agent.session().close());
        }
    }

    /** Placeholder behaviour, replaced by the policy loop in stage 4. */
    private static void wanderFor(List<InWorld> agents, Duration duration) throws InterruptedException {
        Map<InWorld, Perceiver> perceivers = new LinkedHashMap<>();
        for (InWorld agent : agents) {
            perceivers.put(agent, new Perceiver());
            agent.session().send(ClientPackets.chat(
                    "Hello. I am " + agent.character().name() + " and I know nothing yet.", false));
        }

        Random random = new Random();
        Point position = new Point(0, 0);
        long deadline = System.nanoTime() + duration.toNanos();

        while (System.nanoTime() < deadline) {
            for (InWorld agent : agents) {
                Point next = new Point(position.x + random.nextInt(81) - 40, position.y);
                agent.session().send(ClientPackets.move(position, next, (short) 0, (byte) 4, (short) 300));
                position = next;

                for (Observation observation : perceivers.get(agent).perceive(agent.inbox())) {
                    report(agent, observation);
                }
            }
            Thread.sleep(500);
        }

        perceivers.forEach(Launcher::reportUnrecognised);
    }

    /**
     * Prints what an agent perceived. Unrecognised packets are counted rather than printed,
     * since they are numerous and only interesting in aggregate.
     */
    private static void report(InWorld agent, Observation observation) {
        if (observation instanceof Observation.Unrecognised) {
            return;
        }
        log.info("[{}] {}", agent.character().name(), observation);
    }

    private static void reportUnrecognised(InWorld agent, Perceiver perceiver) {
        Map<String, Integer> counts = perceiver.unrecognisedCounts();
        log.info("[{}] {} ticks, {} packet kinds still undecoded:",
                agent.character().name(), perceiver.currentTick(), counts.size());
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> log.info("[{}]    {} x{}", agent.character().name(), e.getKey(), e.getValue()));
    }
}
