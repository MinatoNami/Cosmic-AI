package agents.percept;

import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import tools.PacketCreator;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * Taking a monster off one client's screen is two kill packets, disappear then fade. The
     * server does it to every monster in a map each time an agent arrives there, so reading
     * the second as a death had agents watching whole maps die on the way in.
     */
    @Test
    void aMonsterLeavingTheScreenIsNotADeath() {
        Observation first = decode(PacketCreator.killMonster(9001, 0));
        Observation second = decode(PacketCreator.killMonster(9001, 1));

        assertEquals(9001, assertInstanceOf(Observation.MonsterVanished.class, first).objectId());
        assertNull(second, "the second half repeats the first and is not an observation");
    }

    @Test
    void aDeathBetweenTheTwoHalvesIsStillADeath() {
        decode(PacketCreator.killMonster(9001, 0));

        Observation other = decode(PacketCreator.killMonster(9002, 1));
        decode(PacketCreator.killMonster(9001, 1));
        Observation later = decode(PacketCreator.killMonster(9001, 1));

        assertEquals(9002, assertInstanceOf(Observation.MonsterDied.class, other).objectId());
        assertInstanceOf(Observation.MonsterDied.class, later);
    }

    /**
     * Monster movement has skill fields and a start position ahead of the list that player
     * movement does not, and used to be read with the player layout.
     */
    @Test
    void decodesMonsterMovementWithItsOwnLayout() {
        byte[] list = movementTo(250, 140);
        InPacket movement = new ByteBufInPacket(Unpooled.wrappedBuffer(list));

        Observation observation = decode(PacketCreator.moveMonster(9001, true, 1, 2, 3, 4,
                new Point(-40, 140), movement, list.length));

        Observation.ThingMoved moved = assertInstanceOf(Observation.ThingMoved.class, observation);
        assertEquals(9001, moved.objectId());
        assertEquals(new Point(250, 140), moved.position());
    }

    /** A list of nothing but relative moves still says where the monster started. */
    @Test
    void aMonsterMoveWithNoStatedDestinationFallsBackToItsStart() {
        byte[] list = {0};
        InPacket movement = new ByteBufInPacket(Unpooled.wrappedBuffer(list));

        Observation observation = decode(PacketCreator.moveMonster(9001, false, 0, 0, 0, 0,
                new Point(-40, 140), movement, list.length));

        assertEquals(new Point(-40, 140),
                assertInstanceOf(Observation.ThingMoved.class, observation).position());
    }

    /**
     * Being handed control of a monster is not seeing it: the server has always shown it
     * with the plain spawn first. Built by hand because a real one needs a live Monster.
     */
    @Test
    void takingControlOfAMonsterIsNotASighting() {
        OutPacket control = OutPacket.create(SendOpcode.SPAWN_MONSTER_CONTROL);
        control.writeByte(1);
        control.writeInt(9001);
        control.writeByte(1);
        control.writeInt(100100);
        control.skip(16);
        control.writePos(new Point(10, 0));

        assertInstanceOf(Observation.Unrecognised.class, decode(control));
    }

    /** One absolute move to a point, in the encoding the client sends. */
    private static byte[] movementTo(int x, int y) {
        return new byte[]{
                1,                                      // one command
                0,                                      // absolute move
                (byte) x, (byte) (x >> 8), (byte) y, (byte) (y >> 8),
                0, 0, 0, 0,                             // wobble
                0, 0,                                   // foothold
                2,                                      // stance
                100, 0,                                 // duration
        };
    }

    @Test
    void decodesAMonstersHealthBar() {
        Observation.MonsterHurt hurt = assertInstanceOf(Observation.MonsterHurt.class,
                decode(PacketCreator.showMonsterHP(9001, 35)));

        assertEquals(9001, hurt.objectId());
        assertEquals(35, hurt.hpPercent());
    }

    @Test
    void decodesExperienceGained() {
        Observation.ExpGained gained = assertInstanceOf(Observation.ExpGained.class,
                decode(PacketCreator.getShowExpGain(12, 0, 0, false, true)));

        assertEquals(12, gained.amount());
    }

    @Test
    void decodesMoneyPickedUpAndMoneyHandedOver() {
        Observation.MesoGained picked = assertInstanceOf(Observation.MesoGained.class,
                decode(PacketCreator.getShowMesoGain(40, false)));
        Observation.MesoGained handed = assertInstanceOf(Observation.MesoGained.class,
                decode(PacketCreator.getShowMesoGain(500, true)));

        assertEquals(40, picked.amount());
        assertEquals(500, handed.amount());
    }

    @Test
    void decodesItemsPickedUpAndItemsHandedOver() {
        Observation.ItemGained picked = assertInstanceOf(Observation.ItemGained.class,
                decode(PacketCreator.getShowItemGain(4000019, (short) 3, false)));
        Observation.ItemGained handed = assertInstanceOf(Observation.ItemGained.class,
                decode(PacketCreator.getShowItemGain(2000000, (short) 5, true)));

        assertEquals(4000019, picked.itemId());
        assertEquals(3, picked.quantity());
        assertEquals(2000000, handed.itemId());
        assertEquals(5, handed.quantity());
    }

    @Test
    void stillDecodesAFullBag() {
        assertInstanceOf(Observation.InventoryFull.class, decode(PacketCreator.getShowInventoryFull()));
    }

    @Test
    void aPickUpSaysWhoPickedItUp() {
        Observation.DropTaken picked = assertInstanceOf(Observation.DropTaken.class,
                decode(PacketCreator.removeItemFromMap(700, 2, 42)));
        Observation.DropTaken expired = assertInstanceOf(Observation.DropTaken.class,
                decode(PacketCreator.removeItemFromMap(701, 0, 42)));

        assertEquals(700, picked.objectId());
        assertEquals(42, picked.takenBy());
        assertEquals(Observation.DropTaken.NOBODY, expired.takenBy());
    }

    @Test
    void decodesSomebodyElseBeingHit() {
        Observation.PlayerHurt hurt = assertInstanceOf(Observation.PlayerHurt.class,
                decode(PacketCreator.damagePlayer(-1, 100100, 7, 15, 0, 0, false, 0, true, 0, 0, 0)));

        assertEquals(7, hurt.characterId());
        assertEquals(15, hurt.damage());
        assertEquals(100100, hurt.monsterId());
    }

    @Test
    void decodesTheKeyboard() {
        java.util.Map<Integer, client.keybind.KeyBinding> bound = java.util.Map.of(
                18, new client.keybind.KeyBinding(4, 0),
                83, new client.keybind.KeyBinding(2, 2000000));

        Observation.KeysBound keys = assertInstanceOf(Observation.KeysBound.class,
                decode(PacketCreator.getKeymap(bound)));

        assertEquals(2, keys.keys().size(), "unbound keys are left out");
        assertEquals(new Observation.KeysBound.Binding(2, 2000000), keys.keys().get(83));
    }

    /**
     * A shop's stock, laid out by hand as getNPCShop writes it (building one for real needs a
     * connected client). Throwing stars take six bytes more than a potion, so a star in the
     * middle checks the items after it still line up.
     */
    @Test
    void decodesAShopsStock() {
        OutPacket shop = OutPacket.create(SendOpcode.OPEN_NPC_SHOP);
        shop.writeInt(1012000);
        shop.writeShort(3);
        for (int[] item : new int[][]{{2000000, 50}, {2070000, 500}, {2000001, 160}}) {
            shop.writeInt(item[0]);
            shop.writeInt(item[1]);
            shop.writeInt(0);
            shop.writeInt(0);
            shop.writeInt(0);
            if (item[0] / 10000 == 207) {
                shop.writeShort(0);
                shop.writeInt(0);
                shop.writeShort(0);
                shop.writeShort(800);
            } else {
                shop.writeShort(1);
                shop.writeShort(100);
            }
        }

        Observation.ShopOpened opened = assertInstanceOf(Observation.ShopOpened.class, decode(shop));

        assertEquals(1012000, opened.npcId());
        assertEquals(List.of(2000000, 2070000, 2000001),
                opened.items().stream().map(Observation.ShopOpened.ShopItem::itemId).toList());
        assertEquals(160, opened.items().get(2).price());
        assertEquals(2, opened.items().get(2).index());
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

    /** The server's own "you cannot hold any more", which a full bag answers every pick-up with. */
    @Test
    void readsTheServerSayingTheBagIsFull() {
        assertEquals(new Observation.InventoryFull(1L), decode(PacketCreator.getShowInventoryFull()));
    }

    /** Its neighbour on the same opcode is not the same thing. */
    @Test
    void anItemBeingUnavailableIsNotAFullBag() {
        org.junit.jupiter.api.Assertions.assertFalse(
                decode(PacketCreator.showItemUnavailable()) instanceof Observation.InventoryFull);
    }

    /** How a skill point is known to have landed. */
    @Test
    void readsASkillRising() {
        assertEquals(new Observation.SkillChanged(1L, 1001004, 3, 0),
                decode(PacketCreator.updateSkill(1001004, 3, 0, -1)));
    }
}
