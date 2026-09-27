package agents.percept;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import tools.PacketCreator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Inventory changes in the layout the server's encoder writes. An item is read field by field and the next begins where it ends, so a single field out of
 * place misreads everything after it - which a packet with several changes in it shows.
 */
class InventoryDecodingTest {

    private final ObservationDecoder decoder = new ObservationDecoder();

    private Observation decode(Packet packet) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        int opcode = in.readShort() & 0xFFFF;
        return decoder.decode(1L, opcode, in);
    }

    /**
     * The layout {@code PacketCreator.modifyInventory} and {@code addItemInfo} write, by hand:
     * building real items needs ItemInformationProvider, which needs a database.
     */
    private static OutPacket changes(int count) {
        OutPacket p = OutPacket.create(SendOpcode.INVENTORY_OPERATION);
        p.writeBool(true);
        p.writeByte(count);
        return p;
    }

    private static void header(OutPacket p, int mode, int type, int slot) {
        p.writeByte(mode);
        p.writeByte(type);
        p.writeShort(slot);
    }

    /** addItemInfo for a non-cash equip, with no position (zeroPosition). */
    private static void equip(OutPacket p, int itemId, int upgradeSlots, int str, int watk, int wdef) {
        p.writeByte(1);
        p.writeInt(itemId);
        p.writeBool(false);
        p.writeLong(0);
        p.writeByte(upgradeSlots);
        p.writeByte(0);
        int[] stats = {str, 0, 0, 0, 0, 0, watk, 0, wdef, 0, 0, 0, 0, 0, 0};
        for (int stat : stats) {
            p.writeShort(stat);
        }
        p.writeString("");
        p.writeShort(0);
        p.writeByte(0);
        p.writeByte(1);
        p.writeInt(0);
        p.writeInt(0);
        p.writeLong(0);
        p.writeLong(0);
        p.writeInt(-1);
    }

    /** addItemInfo for a stackable item. */
    private static void stack(OutPacket p, int itemId, int quantity) {
        p.writeByte(2);
        p.writeInt(itemId);
        p.writeBool(false);
        p.writeLong(0);
        p.writeShort(quantity);
        p.writeString("");
        p.writeShort(0);
    }

    @Test
    void readsSeveralChangesInOnePacket() {
        OutPacket p = changes(5);
        header(p, 0, 1, 3);
        equip(p, 1302000, 7, 2, 17, 0);
        header(p, 0, 2, 1);
        stack(p, 2000000, 5);
        header(p, 0, 2, 2);
        stack(p, 2070000, 500);                 // throwing stars carry eight bytes more
        p.writeInt(2);
        p.writeBytes(new byte[]{0x54, 0, 0, 0x34});
        header(p, 1, 4, 4);
        p.writeShort(2);
        header(p, 3, 4, 9);

        List<Observation.InventoryChanged.Change> changes =
                assertInstanceOf(Observation.InventoryChanged.class, decode(p)).changes();
        assertEquals(5, changes.size());

        Item added = changes.get(0).item();
        assertEquals(1302000, added.itemId());
        assertEquals(Item.EQUIP, added.type());
        assertEquals(3, added.slot());
        assertEquals(17, added.stats().watk());
        assertEquals(2, added.stats().str());
        assertEquals(7, added.stats().upgradeSlots());

        Item potion = changes.get(1).item();
        assertEquals(2000000, potion.itemId());
        assertEquals(Item.USE, potion.type());
        assertEquals(5, potion.quantity());
        assertNull(potion.stats());

        assertEquals(500, changes.get(2).item().quantity());

        assertEquals(Observation.InventoryChanged.Change.Kind.RESIZED, changes.get(3).kind());
        assertEquals(2, changes.get(3).quantity());
        assertEquals(4, changes.get(3).slot());

        assertEquals(Observation.InventoryChanged.Change.Kind.REMOVED, changes.get(4).kind());
        assertEquals(9, changes.get(4).slot());
    }

    /** Putting something on is a move to a negative slot; there is no other message for it. */
    @Test
    void readsPuttingSomethingOn() {
        OutPacket p = changes(1);
        header(p, 2, 1, 2);
        p.writeShort(-1);
        p.writeByte(1);                          // the trailing "something was worn" byte

        Observation.InventoryChanged.Change moved =
                assertInstanceOf(Observation.InventoryChanged.class, decode(p)).changes().get(0);
        assertEquals(Observation.InventoryChanged.Change.Kind.MOVED, moved.kind());
        assertEquals(2, moved.slot());
        assertEquals(-1, moved.toSlot());
    }

    /**
     * The bags as entering the world lays them out (addInventoryInfo): worn, worn cash,
     * equipment ending in an int, then use, set-up and etc each ending in a zero byte.
     */
    @Test
    void readsTheBagsCarriedIntoTheWorld() {
        OutPacket p = OutPacket.create(SendOpcode.SET_FIELD);
        for (int limit : new int[]{24, 24, 24, 12, 48}) {
            p.writeByte(limit);
        }
        p.writeLong(0);
        p.writeShort(11);                       // worn in the weapon slot
        equip(p, 1302000, 7, 0, 17, 0);
        p.writeShort(0);
        p.writeShort(0);                        // no cash items worn
        p.writeShort(3);
        equip(p, 1002000, 7, 0, 0, 5);
        p.writeInt(0);
        p.writeByte(1);
        stack(p, 2000000, 5);
        p.writeByte(0);
        p.writeByte(0);                         // nothing in set-up
        p.writeByte(2);
        stack(p, 4000019, 3);
        p.writeByte(0);

        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
        in.readShort();
        ItemReader.Bags bags = ItemReader.readBags(in);

        assertEquals(12, bags.slotLimits()[Item.ETC]);
        assertEquals(List.of(-11, 3, 1, 2), bags.items().stream().map(Item::slot).toList());
        assertEquals(List.of(1302000, 1002000, 2000000, 4000019),
                bags.items().stream().map(Item::itemId).toList());
        assertEquals(5, bags.items().get(1).stats().wdef());
        assertEquals(3, bags.items().get(3).quantity());
    }

    /** The server sends an empty change to let the client act again; nothing happened. */
    @Test
    void anEmptyChangeIsNotAnObservation() {
        assertNull(decode(PacketCreator.getInventoryFull()));
    }

    @Test
    void anEmptyStatUpdateIsNotAnObservation() {
        assertNull(decode(PacketCreator.enableActions()));
    }
}
