package agents.protocol;

import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Read the way HealOvertimeHandler reads it: eight bytes skipped, then health, then mana. */
class HealOverTimeTest {

    @Test
    void carriesHealthThenManaWhereTheServerLooks() {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(ClientPackets.healOverTime(10, 3).getBytes()));

        assertEquals(RecvOpcode.HEAL_OVER_TIME.getValue(), in.readShort());
        in.skip(8);
        assertEquals(10, in.readShort());
        assertEquals(3, in.readShort());
    }
}
