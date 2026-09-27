package agents.mind;

import agents.percept.Item;
import agents.percept.Observation;
import agents.protocol.ClientPackets;
import agents.world.WorldModel;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server refuses a pick-up into a full bag, so taking a quest item means making room for it. */
class MakingRoomTest {

    private static WorldModel fullBagWith(List<Item> etc) {
        WorldModel world = new WorldModel();
        world.update(new Observation.MapEntered(1, 108000300, 0));
        world.update(new Observation.InventoryShown(1, 0, Map.of(1, 24, 2, 24, 3, 24, 4, etc.size(), 5, 24), etc));
        world.movedTo(new Point(0, 0));
        return world;
    }

    @Test
    void dropsAnOrdinaryStackForAMarbleWithNowhereToGo() {
        WorldModel world = fullBagWith(List.of(
                new Item(4, 1, 4031013, 12, null),          // the marbles so far
                new Item(4, 2, 4000000, 40, null),          // snail shells
                new Item(4, 3, 4000019, 3, null)));         // a few of something else
        world.update(new Observation.DropAppeared(2, 900, 4031013, false, new Point(30, 0)));

        var drop = new MakingRoom().step(world).orElseThrow();

        assertArrayEquals(ClientPackets.moveItem(4, 3, 0, 3).getBytes(), drop.getBytes(),
                "the smallest ordinary stack goes, never the marbles");
    }

    @Test
    void doesNothingWhenNoQuestItemIsWaiting() {
        WorldModel world = fullBagWith(List.of(new Item(4, 1, 4000000, 40, null)));
        world.update(new Observation.DropAppeared(2, 900, 4000000, false, new Point(30, 0)));

        assertTrue(new MakingRoom().step(world).isEmpty(), "a full bag is no reason to drop things for more shells");
    }

    @Test
    void neverDropsWhatAQuestNeeds() {
        WorldModel world = fullBagWith(List.of(new Item(4, 1, 4031013, 29, null)));
        world.update(new Observation.DropAppeared(2, 900, 4031013, false, new Point(30, 0)));

        assertTrue(new MakingRoom().step(world).isEmpty(), "its only stack is marbles; nothing to drop");
    }
}
