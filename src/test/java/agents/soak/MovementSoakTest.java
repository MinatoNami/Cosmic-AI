package agents.soak;

import agents.Agent;
import agents.Mind;
import agents.mind.Disposition;
import agents.mind.ReflexPolicy;
import agents.net.LoginFlow;
import agents.net.LoginFlow.Credentials;
import agents.net.LoginFlow.InWorld;
import agents.observe.Watcher;
import agents.protocol.ClientPackets;
import agents.trace.Trace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs an agent against a real server and checks what a player in the same map would have
 * seen it do.
 *
 * Every movement bug this project has had was invisible to every other kind of test. The
 * server reads a move packet for its destination and discards the pose and the path, so an
 * agent that teleported, that walked through platforms, and that spent four minutes stepping
 * onto the spot it was already standing on all passed the unit tests, logged nothing, and
 * were found by a person looking at the screen. Three separate times, in one evening, in one
 * method.
 *
 * <p>So this asserts on the only thing that could have caught them: the broadcasts themselves,
 * over a stretch of time long enough for behaviour to show. It needs a server and a minute,
 * which is why it does not run unless told:
 *
 * <pre>
 *   SOAK_SERVER=127.0.0.1:8484 mvn test -Dtest=MovementSoakTest
 * </pre>
 *
 * <p>Both characters are created fresh each run, because a character remembers where it last
 * stood and the observer has to be in the same map as the agent for any of this to mean
 * anything. New characters always start in the same place. The cost is a couple of abandoned
 * characters per run on whatever server you point it at, which is a fair price on the sort of
 * server you would point it at.
 */
@EnabledIfEnvironmentVariable(named = "SOAK_SERVER", matches = ".+")
class MovementSoakTest {

    /** Long enough for behaviour; short enough to sit through. */
    private static final long WATCH_SECONDS = 90;

    /** Ground an agent should cover in that time if it is going anywhere at all. */
    private static final int PIXELS_WORTH_OF_WANDERING = 200;

    /**
     * The furthest a single broadcast step may carry a character.
     *
     * A walking step is about 75 pixels. Generous headroom here, because the thing being
     * caught is a packet that described an entire journey as one fragment - five hundred
     * pixels in three hundred milliseconds - and not an ordinary long stride.
     */
    private static final int FURTHEST_PLAUSIBLE_STEP = 200;

    @TempDir
    Path traces;

    @Test
    void anAgentLooksLikeSomethingWalkingAround() throws Exception {
        String[] hostAndPort = System.getenv("SOAK_SERVER").split(":");
        String host = hostAndPort[0];
        int port = hostAndPort.length > 1 ? Integer.parseInt(hostAndPort[1]) : 8484;
        String run = String.valueOf(System.currentTimeMillis() % 100000);

        InWorld observing = login(host, port, "watch" + run, "Watch" + run);
        InWorld acting = login(host, port, "soak" + run, "Soak" + run);

        Mind mind = new Mind("Soak" + run, Trace.toFile(traces.resolve("soak.jsonl"), "Soak" + run));
        Agent agent = new Agent(acting, mind,
                new ReflexPolicy(new Random(run.hashCode()), Disposition.WANDERER),
                Disposition.WANDERER);
        Thread running = new Thread(agent, "soak-agent");
        running.start();

        Watcher watcher = new Watcher();
        long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(WATCH_SECONDS);
        while (System.currentTimeMillis() < until) {
            watcher.watch(observing.inbox());
            TimeUnit.MILLISECONDS.sleep(100);
        }

        agent.stop();
        running.join(TimeUnit.SECONDS.toMillis(5));
        observing.session().close();
        mind.close();

        int who = agent.world().characterId();
        List<Watcher.Step> steps = watcher.stepsBy(who);
        List<Watcher.Swing> swings = watcher.swingsBy(who);

        assertFalse(steps.isEmpty(),
                "the map was never told this agent did anything - was the observer in the same map?");

        // It went somewhere. Catches an agent stepping onto the spot it is already standing
        // on, which looks identical to a working agent in every log the project has.
        int leftmost = steps.stream().mapToInt(Watcher.Step::x).min().orElseThrow();
        int rightmost = steps.stream().mapToInt(Watcher.Step::x).max().orElseThrow();
        assertTrue(rightmost - leftmost > PIXELS_WORTH_OF_WANDERING,
                "covered only " + (rightmost - leftmost) + " pixels in " + WATCH_SECONDS
                        + "s, across " + steps.size() + " steps - it is not going anywhere");

        // It walked there. Catches a character broadcasting a standing pose while moving.
        assertTrue(steps.stream().allMatch(Watcher.Step::isWalking),
                "some steps went out in a standing pose, so onlookers see it slide rather than walk");

        // It walked rather than jumped. Catches a whole journey sent as one fragment.
        for (int i = 1; i < steps.size(); i++) {
            int jump = Math.abs(steps.get(i).x() - steps.get(i - 1).x());
            assertTrue(jump <= FURTHEST_PLAUSIBLE_STEP,
                    "a single step moved " + jump + " pixels, which reads as a teleport");
        }

        // Anything it swung has to be drawable. Conditional because a town has no monsters in
        // it, and an agent that found nothing to hit has not failed at anything.
        assertTrue(swings.stream().allMatch(Watcher.Swing::wouldAnimate),
                "swings went out that no client can draw: " + swings);
    }

    private static InWorld login(String host, int port, String account, String character)
            throws Exception {
        LoginFlow flow = new LoginFlow(host, port, 0, 1, new Random(account.hashCode()));
        InWorld connection = flow.enterWorld(new Credentials(account, "soakpass"), character);
        connection.session().send(ClientPackets.mapTransitionComplete());
        return connection;
    }
}
