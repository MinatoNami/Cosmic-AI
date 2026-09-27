package agents.mind;

import agents.percept.Item;
import agents.percept.Observation;
import agents.percept.Observation.InventoryChanged.Change;
import agents.world.EquipInfo;
import agents.world.WorldModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WardrobeTest {

    private static final int OLD_SWORD = 1302000;
    private static final int GOOD_SWORD = 1302001;
    private static final int HEAVY_SWORD = 1302002;
    private static final int WAND = 1372000;
    private static final int OVERALL = 1051000;
    private static final int PANTS = 1060002;

    private static EquipInfo tooltip(int itemId) {
        return switch (itemId) {
            case HEAVY_SWORD -> new EquipInfo(new int[]{-11}, 30, 0, 0, 0, 0, 0, false, false);
            case WAND -> new EquipInfo(new int[]{-11}, 0, 2, 0, 0, 0, 0, false, false);
            case OVERALL -> new EquipInfo(new int[]{-5}, 0, 0, 0, 0, 0, 0, false, true);
            case PANTS -> new EquipInfo(new int[]{-6}, 0, 0, 0, 0, 0, 0, false, false);
            default -> new EquipInfo(new int[]{-11}, 0, 0, 0, 0, 0, 0, false, false);
        };
    }

    private static Item.EquipStats attack(int watk) {
        return new Item.EquipStats(0, 0, 0, 0, 0, 0, watk, 0, 0, 0, 0, 0, 0, 0, 7);
    }

    private static Item.EquipStats defence(int wdef) {
        return new Item.EquipStats(0, 0, 0, 0, 0, 0, 0, 0, wdef, 0, 0, 0, 0, 0, 7);
    }

    private final WorldModel world = new WorldModel((map, portal) -> Optional.empty());
    private final Wardrobe wardrobe = new Wardrobe(WardrobeTest::tooltip);

    private void beginnerCarrying(Item... items) {
        world.update(new Observation.SelfDescribed(1, 2, "Agent0", 10, 0, 10000, 0,
                Map.of("STR", 20, "DEX", 5, "INT", 4, "LUK", 4)));
        world.update(new Observation.InventoryShown(2, 0, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24),
                List.of(items)));
    }

    @Test
    void putsOnAHarderHittingWeapon() {
        beginnerCarrying(new Item(1, -11, OLD_SWORD, 1, attack(10)), new Item(1, 4, GOOD_SWORD, 1, attack(17)));

        Wardrobe.Change change = wardrobe.step(world).orElseThrow();

        assertEquals(GOOD_SWORD, change.itemId());
        assertEquals(4, change.fromSlot());
        assertEquals(-11, change.toSlot());
    }

    @Test
    void keepsWhatIsBetterOn() {
        beginnerCarrying(new Item(1, -11, GOOD_SWORD, 1, attack(17)), new Item(1, 4, OLD_SWORD, 1, attack(10)));

        assertTrue(wardrobe.step(world).isEmpty());
    }

    /** Requirements printed in red are not worth asking the server about. */
    @Test
    void leavesAloneWhatItIsNotLevelOrJobEnoughFor() {
        beginnerCarrying(new Item(1, 4, HEAVY_SWORD, 1, attack(40)), new Item(1, 5, WAND, 1, attack(40)));

        assertTrue(wardrobe.step(world).isEmpty(), "level 30 sword at level 10, and a magician's wand");
    }

    @Test
    void anEmptySlotIsFilled() {
        beginnerCarrying(new Item(1, 4, PANTS, 1, defence(3)));

        assertEquals(-6, wardrobe.step(world).orElseThrow().toSlot());
    }

    /** Trousers would take the overall off; worth it only if they are better than it. */
    @Test
    void doesNotSwapAGoodOverallForTrousers() {
        beginnerCarrying(new Item(1, -5, OVERALL, 1, defence(20)), new Item(1, 4, PANTS, 1, defence(3)));

        assertTrue(wardrobe.step(world).isEmpty());
    }

    @Test
    void waitsBetweenChangesAndGivesUpOnWhatTheServerWillNotMove() {
        beginnerCarrying(new Item(1, -11, OLD_SWORD, 1, attack(10)), new Item(1, 4, GOOD_SWORD, 1, attack(17)));

        assertTrue(wardrobe.step(world).isPresent());
        for (int i = 0; i < Wardrobe.PATIENCE + Wardrobe.BETWEEN_CHANGES; i++) {
            assertTrue(wardrobe.step(world).isEmpty(), "nothing moved; waiting, then set aside");
        }
    }

    @Test
    void movesOnOnceTheServerHasMovedIt() {
        beginnerCarrying(new Item(1, -11, OLD_SWORD, 1, attack(10)), new Item(1, 4, GOOD_SWORD, 1, attack(17)),
                new Item(1, 5, PANTS, 1, defence(3)));

        assertEquals(GOOD_SWORD, wardrobe.step(world).orElseThrow().itemId());
        world.update(new Observation.InventoryChanged(3, List.of(Change.moved(1, 4, -11))));
        for (int i = 0; i < Wardrobe.BETWEEN_CHANGES; i++) {
            wardrobe.step(world);
        }

        assertEquals(PANTS, wardrobe.step(world).orElseThrow().itemId());
    }
}
