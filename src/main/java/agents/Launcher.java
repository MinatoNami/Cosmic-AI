package agents;

import agents.net.LoginFlow;
import agents.net.LoginFlow.Credentials;
import agents.net.LoginFlow.InWorld;
import agents.net.PacketInbox;
import agents.protocol.ClientPackets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
            reportOpcodes();
        } finally {
            live.forEach(agent -> agent.session().close());
        }
    }

    /** Placeholder behaviour, replaced by the policy loop in stage 4. */
    private static void wanderFor(List<InWorld> agents, Duration duration) throws InterruptedException {
        for (InWorld agent : agents) {
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
                drainInbox(agent.inbox());
            }
            Thread.sleep(500);
        }
    }

    /**
     * Counts what arrives without interpreting it. The histogram is the input to stage 2:
     * it says which packets are worth decoding first, ranked by what agents actually meet.
     */
    private static void drainInbox(PacketInbox inbox) {
        PacketInbox.Received received;
        while ((received = inbox.poll()) != null) {
            OPCODE_COUNTS.merge(received.opcode(), 1, Integer::sum);
        }
    }

    private static final java.util.Map<Integer, Integer> OPCODE_COUNTS = new java.util.TreeMap<>();

    private static void reportOpcodes() {
        log.info("Opcodes seen ({} distinct):", OPCODE_COUNTS.size());
        OPCODE_COUNTS.entrySet().stream()
                .sorted(java.util.Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .forEach(e -> log.info("  0x{} x{}", Integer.toHexString(e.getKey()), e.getValue()));
    }
}
