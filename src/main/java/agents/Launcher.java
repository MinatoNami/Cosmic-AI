package agents;

import agents.net.LoginFlow;
import agents.net.LoginFlow.Credentials;
import agents.net.LoginFlow.InWorld;
import agents.memory.Belief;
import agents.percept.Observation;
import agents.percept.Perceiver;
import agents.protocol.ClientPackets;
import agents.trace.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.nio.file.Path;
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

    /**
     * Placeholder behaviour, replaced by the policy loop in stage 4. It still writes a real
     * trace: the movement is arbitrary, but the deliberate/act pair around it is the shape
     * every later decision will take, and it gives the replay something to walk.
     */
    private static void wanderFor(List<InWorld> agents, Duration duration) throws InterruptedException {
        Map<InWorld, Perceiver> perceivers = new LinkedHashMap<>();
        Map<InWorld, Mind> minds = new LinkedHashMap<>();
        Path traceDir = Path.of("target", "traces", String.valueOf(System.currentTimeMillis()));

        for (InWorld agent : agents) {
            String name = agent.character().name();
            perceivers.put(agent, new Perceiver());
            minds.put(agent, new Mind(name, Trace.toFile(traceDir.resolve(name + ".jsonl"), name)));
            agent.session().send(ClientPackets.chat(
                    "Hello. I am " + name + " and I know nothing yet.", false));
        }

        try {
            Random random = new Random();
            Point position = new Point(0, 0);
            long deadline = System.nanoTime() + duration.toNanos();

            while (System.nanoTime() < deadline) {
                for (InWorld agent : agents) {
                    Perceiver perceiver = perceivers.get(agent);
                    Mind mind = minds.get(agent);

                    mind.takeAll(perceiver.perceive(agent.inbox()));

                    long tick = perceiver.currentTick();
                    List<Belief> consulted = mind.recall("where can I go", tick, 3);
                    String because = mind.trace().deliberated(tick, "explore the map",
                            consulted.stream().map(Belief::ref).toList(),
                            List.of("MoveTo", "Wait"));

                    Point next = new Point(position.x + random.nextInt(81) - 40, position.y);
                    agent.session().send(ClientPackets.move(position, next, (short) 0, (byte) 4, (short) 300));
                    mind.trace().acted(tick, "MoveTo", Map.of("x", next.x, "y", next.y), because);
                    position = next;
                }
                Thread.sleep(500);
            }
        } finally {
            minds.values().forEach(Mind::close);
            perceivers.forEach(Launcher::reportUnrecognised);
            minds.forEach((agent, mind) -> report(mind));
            log.info("Traces written to {}", traceDir.toAbsolutePath());
        }
    }

    private static void report(Mind mind) {
        log.info("[{}] {} episodes, {} beliefs ({} live)", mind.name(),
                mind.episodic().size(), mind.semantic().size(), mind.semantic().liveBeliefs().size());
        mind.semantic().liveBeliefs().stream()
                .sorted(java.util.Comparator.comparingDouble(Belief::confidence).reversed())
                .limit(8)
                .forEach(b -> log.info("[{}]    {} ({}, from {})",
                        mind.name(), b.asSentence(), String.format("%.2f", b.confidence()), b.supportedBy().size()));
    }

    private static void reportUnrecognised(InWorld agent, Perceiver perceiver) {
        Map<String, Integer> counts = perceiver.unrecognisedCounts();
        log.info("[{}] {} ticks, {} packet kinds still undecoded",
                agent.character().name(), perceiver.currentTick(), counts.size());
    }
}
