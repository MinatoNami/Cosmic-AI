package agents.net;

import net.opcodes.SendOpcode;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Buffers arriving packets so a caller on another thread can wait for a particular one.
 *
 * Login is a strict request/response conversation, and writing it as nested callbacks makes
 * it far harder to follow than it deserves. An inbox lets the flow read top to bottom while
 * netty's event loop stays unblocked.
 */
public class PacketInbox implements MapleSession.Listener {
    private static final Logger log = LoggerFactory.getLogger(PacketInbox.class);

    /** A received packet, positioned just after its opcode - as server handlers see it. */
    public record Received(int opcode, InPacket packet) {
    }

    private final BlockingQueue<Received> queue = new LinkedBlockingQueue<>();
    private volatile boolean disconnected;

    @Override
    public void onPacket(InPacket packet) {
        int opcode = packet.readShort() & 0xFFFF;
        queue.add(new Received(opcode, packet));
    }

    @Override
    public void onDisconnected() {
        disconnected = true;
    }

    public boolean isDisconnected() {
        return disconnected;
    }

    /**
     * Waits for one of {@code wanted}, discarding anything else.
     *
     * Discarding is safe here and only here: the login conversation carries no world state.
     * Once an agent is in a map, every packet matters and goes through the observation
     * decoder instead.
     *
     * @return the matching packet, or null if it did not arrive in time
     */
    public Received await(Duration timeout, SendOpcode... wanted) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Integer> wantedCodes = new ArrayList<>(wanted.length);
        for (SendOpcode opcode : wanted) {
            wantedCodes.add(opcode.getValue());
        }

        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }

            Received received = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (received == null) {
                return null;
            }
            if (wantedCodes.contains(received.opcode())) {
                return received;
            }
            log.trace("Ignoring opcode 0x{} while waiting for {}",
                    Integer.toHexString(received.opcode()), wantedCodes);
        }
    }

    /** Takes whatever has arrived, without waiting. */
    public Received poll() {
        return queue.poll();
    }

    public void clear() {
        queue.clear();
    }
}
