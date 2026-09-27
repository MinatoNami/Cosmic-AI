package agents.world;

import agents.percept.Item;
import agents.percept.Observation;
import agents.percept.Observation.InventoryChanged.Change;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTest {

    private static final Item.EquipStats FIVE_DEFENCE =
            new Item.EquipStats(0, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 7);

    private final Inventory inventory = new Inventory();

    private void enterWith(int etcSlots, Item... items) {
        inventory.update(new Observation.InventoryShown(1, 120,
                Map.of(1, 24, 2, 24, 3, 24, 4, etcSlots, 5, 24), List.of(items)));
    }

    private void change(Change... changes) {
        inventory.update(new Observation.InventoryChanged(2, List.of(changes)));
    }

    @Test
    void knowsNothingUntilTheBagsAreDescribed() {
        assertFalse(inventory.known());
        assertFalse(inventory.anyBagFull(), "not knowing is not the same as full");
    }

    @Test
    void countsWhatIsCarriedAcrossStacks() {
        enterWith(24, new Item(2, 1, 2000000, 5, null), new Item(2, 4, 2000000, 3, null));

        assertEquals(8, inventory.count(2000000));
        assertEquals(1, inventory.firstOf(2000000).orElseThrow().slot());
        assertEquals(120, inventory.meso());
    }

    @Test
    void aFullBagIsFullAndSellingEmptiesIt() {
        enterWith(2, new Item(4, 1, 4000019, 1, null), new Item(4, 2, 4000000, 1, null));

        assertTrue(inventory.isFull(Item.ETC));
        assertTrue(inventory.anyBagFull());

        change(Change.removed(4, 1));
        assertFalse(inventory.isFull(Item.ETC));
    }

    @Test
    void puttingSomethingOnSwapsItWithWhatWasWorn() {
        enterWith(24, new Item(1, -1, 1002000, 1, FIVE_DEFENCE), new Item(1, 3, 1002001, 1, FIVE_DEFENCE));

        change(Change.moved(1, 3, -1));

        assertEquals(1002001, inventory.wornAt(-1).orElseThrow().itemId());
        assertEquals(1002000, inventory.carried(Item.EQUIP).get(0).itemId());
        assertEquals(3, inventory.carried(Item.EQUIP).get(0).slot());
    }

    @Test
    void addsUpTheDefenceOfWhatIsWorn() {
        enterWith(24, new Item(1, -1, 1002000, 1, FIVE_DEFENCE), new Item(1, -5, 1040002, 1, FIVE_DEFENCE),
                new Item(1, 3, 1002001, 1, FIVE_DEFENCE));

        assertEquals(10, inventory.wornDefence(), "carried armour protects nobody");
    }

    @Test
    void followsStacksAndMoney() {
        enterWith(24, new Item(2, 1, 2000000, 5, null));

        change(Change.resized(2, 1, 4));
        inventory.update(new Observation.StatsChanged(3, Map.of("MESO", 150)));

        assertEquals(4, inventory.count(2000000));
        assertEquals(150, inventory.meso());
    }
}
