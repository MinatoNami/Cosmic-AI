package agents.observe;

import agents.net.LoginFlow;
import agents.net.LoginFlow.Credentials;
import agents.net.LoginFlow.InWorld;
import agents.protocol.ClientPackets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Stands in a map and reports what the other characters in it appear to be doing.
 *
 * <pre>
 *   java -cp ... agents.observe.Watch [host] [port] [seconds]
 * </pre>
 *
 * The point is to answer "does this look right to a player?" without being a player. It only
 * hears about the map it is standing in, so put it where the agents are - the character it
 * logs in as remembers where it last stood, like any other.
 */
public class Watch {
    private static final Logger log = LoggerFactory.getLogger(Watch.class);

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8484;
        long seconds = args.length > 2 ? Long.parseLong(args[2]) : 30;

        LoginFlow flow = new LoginFlow(host, port, 0, 1, new Random("watcher".hashCode()));
        InWorld me = flow.enterWorld(new Credentials("watcher", "watcherpass"), "Watcher");
        me.session().send(ClientPackets.mapTransitionComplete());

        Watcher watcher = new Watcher();
        log.info("Watching for {}s. Anything below is what a player in this map would see.", seconds);

        long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < until) {
            watcher.watch(me.inbox());
            TimeUnit.MILLISECONDS.sleep(200);
        }
        me.session().close();

        report(watcher);
    }

    private static void report(Watcher watcher) {
        List<Watcher.Swing> swings = watcher.swings();
        List<Watcher.Step> steps = watcher.steps();

        System.out.println("\n--- swings seen: " + swings.size() + " ---");
        swings.stream().limit(8).forEach(s -> System.out.printf(
                "  character %d: display=%d direction=%d stance=0x%02X speed=%d targets=%d -> %s%n",
                s.characterId(), s.display(), s.direction(), s.stance(), s.speed(), s.targets(),
                s.wouldAnimate() ? "would animate" : "NOTHING TO DRAW"));

        System.out.println("\n--- steps seen: " + steps.size() + " ---");
        steps.stream().limit(8).forEach(s -> System.out.printf(
                "  character %d: to (%d,%d) stance=%d over %dms -> %s%n",
                s.characterId(), s.x(), s.y(), s.stance(), s.durationMillis(),
                s.isWalking() ? "walking" : "STANDING STILL WHILE MOVING"));

        if (swings.isEmpty() && steps.isEmpty()) {
            System.out.println("\nNobody else did anything here. Is anyone in this map?");
        }
    }
}
