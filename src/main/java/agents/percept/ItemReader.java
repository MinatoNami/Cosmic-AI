package agents.percept;

import net.packet.InPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads items in the one encoding the server uses for all of them.
 *
 * Mirrors {@code PacketCreator.addItemInfo}, which writes equipment, stackable items and pets
 * three different ways behind a shared header, and {@code addInventoryInfo}, which lays the
 * bags out one after another with a different terminator after each. Every field is read in
 * order even where it is thrown away, because the next item starts where this one ends.
 */
final class ItemReader {
    private ItemReader() {
    }

    private static final int EQUIPMENT = 1;
    private static final int PET = 3;

    /**
     * One item, whose bag and slot the caller already knows.
     *
     * @see tools.PacketCreator#addItemInfo(net.packet.OutPacket, client.inventory.Item, boolean)
     */
    static Item read(InPacket p, int type, int slot) {
        int kind = p.readUnsignedByte();
        int itemId = p.readInt();
        boolean cash = p.readByte() != 0;
        if (cash) {
            p.readLong();                               // cash serial, pet or ring id
        }
        p.readLong();                                   // expiry

        if (kind == PET) {
            p.skip(13);                                 // name
            p.readByte();                               // level
            p.readShort();                              // closeness
            p.readByte();                               // fullness
            p.readLong();                               // expiry, again
            p.skip(10);                                 // attribute, skill, life, attribute
            return new Item(type, slot, itemId, 1, null);
        }
        if (kind != EQUIPMENT) {
            int quantity = p.readShort();
            p.readString();                             // owner
            p.readShort();                              // flag
            if (isRechargeable(itemId)) {
                p.skip(8);
            }
            return new Item(type, slot, itemId, quantity, null);
        }

        int upgradeSlots = p.readUnsignedByte();
        p.readByte();                                   // times upgraded
        int str = p.readShort();
        int dex = p.readShort();
        int intel = p.readShort();
        int luk = p.readShort();
        int hp = p.readShort();
        int mp = p.readShort();
        int watk = p.readShort();
        int matk = p.readShort();
        int wdef = p.readShort();
        int mdef = p.readShort();
        int acc = p.readShort();
        int avoid = p.readShort();
        p.readShort();                                  // hands
        int speed = p.readShort();
        int jump = p.readShort();
        p.readString();                                 // owner
        p.readShort();                                  // flag
        p.skip(cash ? 10 : 18);                         // item level and experience, or cash padding
        p.readLong();
        p.readInt();
        return new Item(type, slot, itemId, 1, new Item.EquipStats(str, dex, intel, luk, hp, mp,
                watk, matk, wdef, mdef, acc, avoid, speed, jump, upgradeSlots));
    }

    /** Throwing stars and bullets carry a little more, and are refilled rather than stacked. */
    static boolean isRechargeable(int itemId) {
        int kind = itemId / 10000;
        return kind == 207 || kind == 233;
    }

    /** Everything the bags hold on entering the world, and how big each one is. */
    record Bags(int[] slotLimits, List<Item> items) {
    }

    /**
     * The bags as the character-info packet lays them out: worn items, worn cash items,
     * equipment, use, set-up and etc, each list ending where a zero slot appears. The cash
     * bag comes last and is left unread - nothing after it is needed, and nothing in it can
     * be sold or worn for stats.
     *
     * @see tools.PacketCreator#addInventoryInfo
     */
    static Bags readBags(InPacket p) {
        int[] limits = new int[6];
        for (int type = 1; type <= 5; type++) {
            limits[type] = p.readUnsignedByte();
        }
        p.readLong();

        List<Item> items = new ArrayList<>();
        readWorn(p, items, 0);
        readWorn(p, items, -100);                       // cash items worn over the top
        int slot;
        while ((slot = p.readShort()) != 0) {
            items.add(read(p, Item.EQUIP, slot));
        }
        p.readShort();                                  // the equipment list ends with an int
        for (int type = Item.USE; type <= Item.ETC; type++) {
            while ((slot = p.readUnsignedByte()) != 0) {
                items.add(read(p, type, slot));
            }
        }
        return new Bags(limits, List.copyOf(items));
    }

    /** Worn items are listed by where on the body, written as positive numbers. */
    private static void readWorn(InPacket p, List<Item> items, int offset) {
        int position;
        while ((position = p.readShort()) != 0) {
            items.add(read(p, Item.EQUIP, -position + offset));
        }
    }
}
