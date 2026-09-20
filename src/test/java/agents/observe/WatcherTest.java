package agents.observe;

import agents.net.PacketInbox;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.ByteBufInPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes broadcasts built the way the server builds them, so the instrument is checked
 * against the thing it is meant to measure rather than against itself.
 */
class WatcherTest {

    /** @see tools.PacketCreator#addAttackBody */
    private static byte[] attackBroadcast(int characterId, int display, int direction,
                                          int stance, int speed) {
        OutPacket p = OutPacket.create(SendOpcode.CLOSE_RANGE_ATTACK);
        p.writeInt(characterId);
        p.writeByte((1 << 4) | 1);
        p.writeByte(0x5B);
        p.writeByte(0);             // skill level 0, so no skill id follows
        p.writeByte(display);
        p.writeByte(direction);
        p.writeByte(stance);
        p.writeByte(speed);
        p.writeByte(0x0A);
        p.writeInt(0);              // projectile
        return p.getBytes();
    }

    /** @see tools.PacketCreator#movePlayer */
    private static byte[] moveBroadcast(int characterId, int x, int y, int stance, int duration) {
        OutPacket p = OutPacket.create(SendOpcode.MOVE_PLAYER);
        p.writeInt(characterId);
        p.writeInt(0);
        p.writeByte(1);             // one command
        p.writeByte(0);             // absolute move
        p.writeShort(x);
        p.writeShort(y);
        p.writeShort(0);
        p.writeShort(0);
        p.writeShort(0);            // foothold
        p.writeByte(stance);
        p.writeShort(duration);
        return p.getBytes();
    }

    /** Feeds bytes in the way a session does: opcode read off, body handed over. */
    private static Watcher watching(byte[]... broadcasts) {
        PacketInbox inbox = new PacketInbox();
        for (byte[] bytes : broadcasts) {
            inbox.onPacket(new ByteBufInPacket(Unpooled.wrappedBuffer(bytes)));
        }
        Watcher watcher = new Watcher();
        watcher.watch(inbox);
        return watcher;
    }

    @Test
    void readsTheAnimationFieldsOutOfASwing() {
        Watcher watcher = watching(attackBroadcast(9, 0, 1, 0x80, 4));

        Watcher.Swing swing = watcher.swingsBy(9).get(0);
        assertEquals(0, swing.display());
        assertEquals(1, swing.direction());
        assertEquals(0x80, swing.stance());
        assertEquals(4, swing.speed());
        assertTrue(swing.wouldAnimate());
    }

    /**
     * The state the agents shipped in for the life of the project: a swing the server accepts,
     * relays, and which every other client draws as nothing at all.
     */
    @Test
    void noticesASwingWithNothingToDraw() {
        Watcher watcher = watching(attackBroadcast(9, 0, 0, 0, 0));

        assertFalse(watcher.swingsBy(9).get(0).wouldAnimate());
    }

    @Test
    void readsPoseAndDestinationOutOfAStep() {
        Watcher watcher = watching(moveBroadcast(9, -177, -205, 0, 600));

        Watcher.Step step = watcher.stepsBy(9).get(0);
        assertEquals(-177, step.x());
        assertEquals(-205, step.y());
        assertEquals(600, step.durationMillis());
        assertTrue(step.isWalking());
    }

    /** Stance 4 is standing. A character broadcasting it while changing position is sliding. */
    @Test
    void noticesACharacterMovingInAStandingPose() {
        Watcher watcher = watching(moveBroadcast(9, 100, 0, 4, 300));

        assertFalse(watcher.stepsBy(9).get(0).isWalking());
    }

    @Test
    void separatesWhoDidWhat() {
        Watcher watcher = watching(
                attackBroadcast(9, 0, 0, 0x80, 4),
                attackBroadcast(10, 0, 0, 0x80, 4),
                moveBroadcast(9, 5, 5, 1, 300));

        assertEquals(1, watcher.swingsBy(9).size());
        assertEquals(1, watcher.swingsBy(10).size());
        assertEquals(1, watcher.stepsBy(9).size());
        assertEquals(0, watcher.stepsBy(10).size());
    }

    /**
     * Every step here is a well-formed walking step, and the character has not moved an inch.
     * This is what four minutes of a real agent looked like, and no single-packet predicate
     * can see it.
     */
    @Test
    void noticesACharacterWalkingOnTheSpot() {
        Watcher watcher = watching(
                moveBroadcast(9, 836, 605, 0, 1),
                moveBroadcast(9, 836, 605, 0, 1),
                moveBroadcast(9, 836, 605, 0, 1),
                moveBroadcast(9, 836, 605, 0, 1));

        assertTrue(watcher.stepsBy(9).stream().allMatch(Watcher.Step::isWalking),
                "each step on its own looks perfectly fine, which is the problem");
        assertEquals(0, watcher.groundCovered(9));
        assertEquals(3, watcher.longestRunOnTheSpot(9));
    }

    @Test
    void measuresGroundCoveredBySomethingActuallyWalking() {
        Watcher watcher = watching(
                moveBroadcast(9, 100, 485, 0, 300),
                moveBroadcast(9, 175, 485, 0, 300),
                moveBroadcast(9, 250, 485, 0, 300));

        assertEquals(150, watcher.groundCovered(9));
        assertEquals(0, watcher.longestRunOnTheSpot(9));
    }
}
