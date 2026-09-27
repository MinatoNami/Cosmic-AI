package agents.mind;

import agents.Mind;
import agents.percept.Item;
import agents.percept.Observation;
import agents.trace.Trace;
import agents.world.EquipInfo;
import agents.world.WorldModel;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopkeepingTest {

    private static final int RED_POTION = 2000000;
    private static final int ORANGE_POTION = 2000001;
    private static final int DUD = 2000009;
    private static final int UNTRIED = 2010000;
    private static final int RETURN_SCROLL = 2030000;
    private static final int SNAIL_SHELL = 4000019;
    private static final int WORN_SWORD = 1302000;
    private static final int WORSE_SWORD = 1302001;
    private static final int BETTER_SWORD = 1302002;

    @TempDir
    Path traceDir;

    private Mind mind;
    private final WorldModel world = new WorldModel((map, portal) -> Optional.empty());
    private final Shopkeeping shop = new Shopkeeping(id ->
            new EquipInfo(new int[]{-11}, id == BETTER_SWORD ? 30 : 0, 0, 0, 0, 0, 0, false, false));

    @BeforeEach
    void wake() {
        mind = new Mind("Test", Trace.toFile(traceDir.resolve("t.jsonl"), "Test"));
        mind.take(new Observation.NoticeShown(1, "hello"));
        world.update(new Observation.SelfDescribed(1, 2, "Agent0", 10, 0, 10000, 0, Map.of("STR", 20)));
    }

    @AfterEach
    void sleep() {
        mind.close();
    }

    private static Item.EquipStats attack(int watk) {
        return new Item.EquipStats(0, 0, 0, 0, 0, 0, watk, 0, 0, 0, 0, 0, 0, 0, 7);
    }

    private void carrying(int meso, Item... items) {
        world.update(new Observation.InventoryShown(2, meso, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24),
                List.of(items)));
    }

    private static Observation.ShopOpened selling(Observation.ShopOpened.ShopItem... items) {
        return new Observation.ShopOpened(3, 1012000, List.of(items));
    }

    private static InPacket read(Packet packet) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        assertEquals(RecvOpcode.NPC_SHOP.getValue(), in.readShort() & 0xFFFF);
        return in;
    }

    @Test
    void sellsWhatIsSpareAndKeepsWhatMightBeUseful() {
        mind.infer("item:" + DUD, "restores_hp", "false", 1);
        carrying(0,
                new Item(1, -11, WORN_SWORD, 1, attack(17)),
                new Item(1, 1, WORSE_SWORD, 1, attack(10)),
                new Item(1, 2, BETTER_SWORD, 1, attack(40)),
                new Item(2, 1, DUD, 4, null),
                new Item(2, 2, UNTRIED, 4, null),
                new Item(2, 3, RETURN_SCROLL, 1, null),
                new Item(4, 1, SNAIL_SHELL, 30, null));

        List<Integer> spare = shop.spare(world, mind).stream().map(Item::itemId).toList();

        assertEquals(List.of(SNAIL_SHELL, WORSE_SWORD, DUD), spare,
                "not the better sword it will grow into, not what it has not tried, not the scroll");
    }

    @Test
    void sellsOneThingAStepThenLeaves() {
        carrying(0, new Item(4, 1, SNAIL_SHELL, 30, null));
        shop.opened(selling(), world, mind);

        InPacket sale = read(shop.next(world, mind));
        assertEquals(1, sale.readByte(), "sell");
        assertEquals(1, sale.readShort());
        assertEquals(SNAIL_SHELL, sale.readInt());
        assertEquals(30, sale.readShort(), "the whole stack");

        InPacket leave = read(shop.next(world, mind));
        assertEquals(3, leave.readByte(), "walk away");
        assertFalse(shop.atCounter());
        assertNull(shop.next(world, mind));
    }

    @Test
    void topsUpOnWhatItKnowsHealsAsFarAsItsMoneyGoes() {
        mind.infer("item:" + RED_POTION, "restores_hp", "true", 1);
        carrying(500, new Item(2, 1, RED_POTION, 5, null));
        shop.opened(selling(new Observation.ShopOpened.ShopItem(0, ORANGE_POTION, 160),
                new Observation.ShopOpened.ShopItem(1, RED_POTION, 50)), world, mind);

        InPacket buy = read(shop.next(world, mind));

        assertEquals(0, buy.readByte(), "buy");
        assertEquals(1, buy.readShort(), "by its place in the list");
        assertEquals(RED_POTION, buy.readInt());
        assertEquals(10, buy.readShort(), "500 mesos at 50 each, short of the 25 wanted");
    }

    /** Knowing of nothing that heals, it pays to find out. */
    @Test
    void buysAFewOfTheCheapestDrinkToTryWhenItKnowsNothing() {
        carrying(500);
        shop.opened(selling(new Observation.ShopOpened.ShopItem(0, ORANGE_POTION, 160),
                new Observation.ShopOpened.ShopItem(1, RED_POTION, 50),
                new Observation.ShopOpened.ShopItem(2, RETURN_SCROLL, 10)), world, mind);

        InPacket buy = read(shop.next(world, mind));

        assertEquals(0, buy.readByte());
        assertEquals(1, buy.readShort());
        assertEquals(RED_POTION, buy.readInt());
        assertEquals(Shopkeeping.TO_TRY, buy.readShort());
    }

    @Test
    void buysNothingWithNoMoney() {
        mind.infer("item:" + RED_POTION, "restores_hp", "true", 1);
        carrying(0);
        shop.opened(selling(new Observation.ShopOpened.ShopItem(0, RED_POTION, 50)), world, mind);

        assertEquals(3, read(shop.next(world, mind)).readByte(), "straight out again");
        assertTrue(!shop.atCounter());
    }
}
