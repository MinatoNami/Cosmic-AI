package agents;

import agents.net.LoginFlow;
import agents.net.LoginFlow.Credentials;
import agents.net.LoginFlow.InWorld;
import agents.percept.Observation;
import agents.percept.Perceiver;
import agents.protocol.ClientPackets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Random;

/**
 * Puts a question to a running agent and prints what it says back.
 *
 * You can do this from the game too - log in and whisper - but that needs a client, and
 * "why did you just do that" is a question you want to ask while watching a run, not after
 * finding a Windows machine.
 *
 * <pre>
 *   java -cp ... agents.Ask [host] [port] &lt;agentName&gt; &lt;question&gt;
 * </pre>
 *
 * Questions an agent understands: {@code why}, {@code where}, {@code who},
 * {@code know &lt;thing&gt;}.
 */
public class Ask {
    private static final Logger log = LoggerFactory.getLogger(Ask.class);
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(10);

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: Ask <host> <port> <agentName> <question...>");
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String target = args[2];
        String question = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));

        LoginFlow flow = new LoginFlow(host, port, 0, 1, new Random("asker".hashCode()));
        InWorld me = flow.enterWorld(new Credentials("asker", "askerpass"), "Asker");

        try {
            me.session().send(ClientPackets.mapTransitionComplete());
            me.session().send(ClientPackets.whisper(target, question));
            System.out.println("-> " + target + ": " + question);

            String answer = awaitAnswer(me, target);
            System.out.println(answer != null
                    ? "<- " + target + ": " + answer
                    : "<- (no answer - is " + target + " online?)");
        } finally {
            me.session().close();
        }
    }

    private static String awaitAnswer(InWorld me, String target) throws InterruptedException {
        Perceiver perceiver = new Perceiver();
        long deadline = System.nanoTime() + ANSWER_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            for (Observation observation : perceiver.perceive(me.inbox())) {
                if (observation instanceof Observation.WhisperHeard whisper
                        && whisper.speakerName().equalsIgnoreCase(target)) {
                    return whisper.text();
                }
            }
            Thread.sleep(100);
        }
        return null;
    }
}
