package agents.protocol;

import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reads client packets back in the order their server handlers read them. A field out of
 * place does not fail on the server; it reads as some other number and quietly does the
 * wrong thing, so the order is what these pin.
 */
class ClientPacketsTest {

    private static InPacket read(Packet packet, RecvOpcode expected) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        assertEquals(expected.getValue(), in.readShort() & 0xFFFF);
        return in;
    }

    /** @see net.server.channel.handlers.TakeDamageHandler */
    @Test
    void touchDamageIsLaidOutAsTheDamageHandlerReadsIt() {
        InPacket p = read(ClientPackets.touchedByMonster(8, 100100, 9001, true), RecvOpcode.TAKE_DAMAGE);

        p.readInt();
        assertEquals(-1, p.readByte(), "damage from the monster's body");
        p.readByte();
        assertEquals(8, p.readInt());
        assertEquals(100100, p.readInt());
        assertEquals(9001, p.readInt());
        assertEquals(1, p.readByte());
        assertEquals(0, p.available());
    }

    /** @see net.server.channel.handlers.ChangeMapHandler */
    @Test
    void revivingIsAMapChangeFromDyingThatNamesNoPortal() {
        InPacket p = read(ClientPackets.revive(), RecvOpcode.CHANGE_MAP);

        assertEquals(1, p.readByte(), "from dying");
        assertEquals(0, p.readInt(), "not -1, so not a portal");
        assertEquals("", p.readString());
        p.readByte();
        assertEquals(0, p.readByte(), "no wheel of fortune");
        assertEquals(0, p.readByte(), "not chasing");
    }

    /** @see net.server.channel.handlers.ItemMoveHandler */
    @Test
    void puttingSomethingOnIsAMoveToANegativeSlot() {
        InPacket p = read(ClientPackets.moveItem(1, 4, -11, 1), RecvOpcode.ITEM_MOVE);

        p.readInt();
        assertEquals(1, p.readByte());
        assertEquals(4, p.readShort());
        assertEquals(-11, p.readShort());
        assertEquals(1, p.readShort());
    }
}
