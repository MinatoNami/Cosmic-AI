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

    /** A Thief keeps its stars and sells the arrows, bolts and equipment scrolls it cannot use. */
    @Test
    void sellsAmmunitionItsJobCannotFireAndScrollsNothingReads() {
        world.update(new agents.percept.Observation.StatsChanged(1, java.util.Map.of("JOB", 400)));
        carrying(0,
                new Item(2, 1, 2060000, 2000, null),
                new Item(2, 2, 2061000, 2000, null),
                new Item(2, 3, 2070015, 500, null),
                new Item(2, 4, 2041021, 2, null),
                new Item(2, 5, RETURN_SCROLL, 1, null));

        List<Integer> spare = shop.spare(world, mind).stream().map(Item::itemId).toList();

        assertEquals(List.of(2060000, 2061000, 2041021), spare);
    }

    /** The proof of a test, a letter for an instructor: kept, however full the bag. */
    @Test
    void keepsWhatIsForAQuestOrForSomebody() {
        mind.saw("npc:1072000", "asked_you_to", "bring 5 item:4000018", 1);
        carrying(0,
                new Item(4, 1, 4031012, 1, null),
                new Item(4, 2, 4000018, 5, null),
                new Item(4, 3, SNAIL_SHELL, 30, null));

        List<Integer> spare = shop.spare(world, mind).stream().map(Item::itemId).toList();

        assertEquals(List.of(SNAIL_SHELL), spare);
    }

    /**
     * Handed over is not asked for: an event's Monster Cards and a box's Stiff Feathers were
     * kept for having been given, and filled half a Thief's etc bag for good.
     */
    @Test
    void sellsWhatWasHandedOverThatNobodyAsksFor() {
        mind.saw("item:4030012", "given_by", "npc:1095002", 1);
        carrying(0,
                new Item(4, 1, 4031045, 1, null),
                new Item(4, 2, 4030012, 1000, null));

        List<Integer> spare = shop.spare(world, mind).stream().map(Item::itemId).toList();

        assertEquals(List.of(4030012), spare, "the cards go; the ticket, a quest item, stays");
    }

    private static final int WORN_TOP = 1040002;
    private static final int SHORT_OF_DEX = 1040097;
    private static final int NOT_YET_LEVEL = 1040111;
    private static final int WEARABLE = 1040059;

    private final Shopkeeping thiefShop = new Shopkeeping(id -> switch (id) {
        case SHORT_OF_DEX -> new EquipInfo(new int[]{-5}, 50, 0, 0, 90, 0, 140, false, false);
        case NOT_YET_LEVEL -> new EquipInfo(new int[]{-5}, 80, 0, 0, 120, 0, 200, false, false);
        case WEARABLE -> new EquipInfo(new int[]{-5}, 30, 0, 0, 30, 0, 80, false, false);
        default -> new EquipInfo(new int[]{-5}, 0, 0, 0, 0, 0, 0, false, false);
    });

    private static Item.EquipStats top(int luk, int wdef) {
        return new Item.EquipStats(0, 0, 0, luk, 0, 0, 0, 0, wdef, 0, 0, 0, 0, 0, 7);
    }

    /**
     * A level-70 Thief with DEX 38 carried thirty-two pieces of thief gear asking DEX 45 to
     * 100, wore none of them, and kept every one because each was better than what it had on.
     */
    @Test
    void sellsGearItIsOldEnoughForButShortOfTheStatsToWear() {
        world.update(new Observation.StatsChanged(1, Map.of("JOB", 420, "LEVEL", 70, "DEX", 38, "LUK", 319)));
        carrying(0,
                new Item(1, -5, WORN_TOP, 1, top(0, 10)),
                new Item(1, 1, SHORT_OF_DEX, 1, top(5, 60)),
                new Item(1, 2, NOT_YET_LEVEL, 1, top(8, 80)),
                new Item(1, 3, WEARABLE, 1, top(3, 40)));

        List<Integer> spare = thiefShop.spare(world, mind).stream().map(Item::itemId).toList();

        assertEquals(List.of(SHORT_OF_DEX), spare,
                "not what it will be level enough for, not what it can wear now");
    }

    /** Stats it has not been told are not stats it is short of. */
    @Test
    void keepsGearWhileItsStatsAreUnknown() {
        world.update(new Observation.StatsChanged(1, Map.of("JOB", 420, "LEVEL", 70)));
        carrying(0,
                new Item(1, -5, WORN_TOP, 1, top(0, 10)),
                new Item(1, 1, SHORT_OF_DEX, 1, top(5, 60)));

        assertTrue(thiefShop.spare(world, mind).isEmpty());
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

    /** With money enough, the strongest it knows heals rather than the cheapest. */
    @Test
    void buysTheStrongestHealerItCanStockUpOn() {
        mind.infer("item:" + RED_POTION, "restores_hp", "true", 1);
        mind.infer("item:" + ORANGE_POTION, "restores_hp", "true", 1);
        carrying(100_000);
        shop.opened(selling(new Observation.ShopOpened.ShopItem(0, ORANGE_POTION, 160),
                new Observation.ShopOpened.ShopItem(1, RED_POTION, 50)), world, mind);

        InPacket buy = read(shop.next(world, mind));

        assertEquals(0, buy.readByte());
        assertEquals(0, buy.readShort(), "the orange potion");
        assertEquals(ORANGE_POTION, buy.readInt());
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

    /** Due its third job, it keeps two scrolls home: one in the bag, so one more. */
    @Test
    void keepsTwoScrollsHomeOnceDueItsThirdJob() {
        world.update(new Observation.StatsChanged(1, Map.of("JOB", 420, "LEVEL", 70)));
        carrying(100_000, new Item(2, 1, RETURN_SCROLL, 1, null));
        shop.opened(selling(new Observation.ShopOpened.ShopItem(0, RETURN_SCROLL, 400)), world, mind);

        InPacket buy = read(shop.next(world, mind));

        assertEquals(0, buy.readByte(), "buy");
        assertEquals(0, buy.readShort());
        assertEquals(RETURN_SCROLL, buy.readInt());
        assertEquals(1, buy.readShort(), "one more, to make two");
    }

    @Test
    void oneScrollHomeIsEnoughBeforeThen() {
        world.update(new Observation.StatsChanged(1, Map.of("JOB", 420, "LEVEL", 69)));
        carrying(100_000, new Item(2, 1, RETURN_SCROLL, 1, null));
        shop.opened(selling(new Observation.ShopOpened.ShopItem(0, RETURN_SCROLL, 400)), world, mind);

        assertEquals(3, read(shop.next(world, mind)).readByte(), "straight out again");
    }

    @Test
    void buysNothingWithNoMoney() {
        mind.infer("item:" + RED_POTION, "restores_hp", "true", 1);
        carrying(0);
        shop.opened(selling(new Observation.ShopOpened.ShopItem(0, RED_POTION, 50)), world, mind);

        assertEquals(3, read(shop.next(world, mind)).readByte(), "straight out again");
        assertTrue(!shop.atCounter());
    }

    /** Athena's bow was sold because a spear in hand looked better; to a Bowman it is not. */
    @Test
    void aBowmanDoesNotSellItsBowForTheSpearInItsHand() {
        world.update(new Observation.SelfDescribed(1, 2, "Agent1", 14, 300, 100000000, 0,
                Map.of("STR", 4, "DEX", 40)));
        carrying(1000, new Item(1, -11, 1432000, 1, attack(30)), new Item(1, 3, 1452051, 1, attack(10)));

        assertTrue(shop.spare(world, mind).stream().noneMatch(item -> item.itemId() == 1452051));
        assertFalse(Wardrobe.needsAWeapon(world), "it has a bow, in the bag");
    }

    @Test
    void aBowmanWithNoBowNeedsOne() {
        world.update(new Observation.SelfDescribed(1, 2, "Agent1", 14, 300, 100000000, 0,
                Map.of("STR", 4, "DEX", 40)));
        carrying(1000, new Item(1, -11, 1432000, 1, attack(30)));

        assertTrue(Wardrobe.needsAWeapon(world));
    }
}
