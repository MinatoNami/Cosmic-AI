package agents.percept;

import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes packets built by the server's own {@link PacketCreator}.
 *
 * Testing against the real encoder is the point: a decoder written from a handwritten note
 * about the protocol drifts silently the moment the encoder changes, and the symptom is an
 * agent quietly misreading the world rather than a failure anyone would notice.
 */
class ObservationDecoderTest {

    private final ObservationDecoder decoder = new ObservationDecoder();

    /** Packets carry their opcode; the perceiver strips it before decoding, so do the same. */
    private Observation decode(Packet packet) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        int opcode = in.readShort() & 0xFFFF;
        return decoder.decode(1L, opcode, in);
    }

    @Test
    void decodesMonsterDeath() {
        Observation observation = decode(PacketCreator.killMonster(9001, true));

        Observation.MonsterDied died = assertInstanceOf(Observation.MonsterDied.class, observation);
        assertEquals(9001, died.objectId());
    }

    @Test
    void decodesChat() {
        Observation observation = decode(PacketCreator.getChatText(42, "where are the monsters", false, 0));

        Observation.ChatHeard heard = assertInstanceOf(Observation.ChatHeard.class, observation);
        assertEquals(42, heard.speakerId());
        assertEquals("where are the monsters", heard.text());
    }

    @Test
    void decodesPlayerLeaving() {
        Observation observation = decode(PacketCreator.removePlayerFromMap(7));

        Observation.PlayerLeft left = assertInstanceOf(Observation.PlayerLeft.class, observation);
        assertEquals(7, left.characterId());
    }

    /**
     * The scrolling server message writes a flag byte before the string that a plain notice
     * does not, and both share a type. This is the case that reconstruction has to get right.
     */
    @Test
    void decodesServerMessageWithItsFlagByte() {
        Observation observation = decode(PacketCreator.serverMessage("the server is restarting"));

        Observation.NoticeShown notice = assertInstanceOf(Observation.NoticeShown.class, observation);
        assertEquals("the server is restarting", notice.text());
    }

    @Test
    void decodesNoticeWithoutFlagByte() {
        Observation observation = decode(PacketCreator.serverNotice(5, "you feel refreshed"));

        Observation.NoticeShown notice = assertInstanceOf(Observation.NoticeShown.class, observation);
        assertEquals("you feel refreshed", notice.text());
    }

    @Test
    void reportsUnhandledPacketsRatherThanDroppingThem() {
        Observation observation = decode(PacketCreator.enableTV());

        Observation.Unrecognised unrecognised =
                assertInstanceOf(Observation.Unrecognised.class, observation);
        assertEquals("ENABLE_TV", unrecognised.opcodeName());
        assertTrue(unrecognised.bytes() > 0);
    }

    /**
     * A truncated or unexpected packet has to degrade, not throw: one malformed packet must
     * never be able to take an agent off the network.
     */
    @Test
    void survivesAMalformedPacket() {
        InPacket truncated = new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[]{0x01}));

        Observation observation = decoder.decode(1L, net.opcodes.SendOpcode.SET_FIELD.getValue(), truncated);

        assertInstanceOf(Observation.Unrecognised.class, observation);
    }
}
