package agents.percept;

import net.packet.InPacket;

import java.awt.Point;

/**
 * Reads a movement list and reports where the thing ended up.
 *
 * Mirrors {@code AbstractMovementPacketHandler.parseMovement}, but keeps only what an
 * observer needs: the last position the sequence states outright. Relative fragments (jumps,
 * knockbacks) carry offsets rather than positions, so they advance the cursor without
 * contributing an answer - the server treats them the same way.
 */
final class MovementList {
    private MovementList() {
    }

    /** @return the final stated position, or null if the list contained none */
    static Point finalPosition(InPacket p) {
        int commands = p.readUnsignedByte();
        Point last = null;

        for (int i = 0; i < commands; i++) {
            int command = p.readUnsignedByte();
            switch (command) {
                case 0, 5, 17 -> {                  // absolute move
                    int x = p.readShort();
                    int y = p.readShort();
                    p.skip(4);                      // wobble
                    p.readShort();                  // foothold
                    p.readByte();                   // stance
                    p.readShort();                  // duration
                    last = new Point(x, y);
                }
                case 3, 4, 7, 8, 9, 11 -> {         // teleport-like
                    int x = p.readShort();
                    int y = p.readShort();
                    p.skip(4);                      // wobble
                    p.readByte();                   // stance
                    last = new Point(x, y);
                }
                case 1, 2, 6, 12, 13, 16, 18, 19, 20, 22 -> p.skip(7);   // relative
                case 10 -> p.readByte();            // equipment change
                case 14 -> p.skip(9);               // jump down
                case 15 -> p.skip(15);          // jump down; the server does not treat it
                                                // as an absolute position either
                case 21 -> p.skip(3);
                default -> {
                    // An unknown command means the rest of the list cannot be trusted, so
                    // stop rather than read garbage into a position.
                    return last;
                }
            }
        }
        return last;
    }
}
