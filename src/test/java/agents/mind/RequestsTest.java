package agents.mind;

import agents.percept.Item;
import agents.percept.Observation;
import agents.world.Inventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestsTest {

    @Test
    void usesWhatItWasAskedToAndThenStopsWaiting() {
        Inventory bag = carrying(2010007, 2);
        Requests requests = new Requests();
        requests.use(2000, 2010007, 2);

        List<Integer> used = new ArrayList<>();
        List<Integer> finished = new ArrayList<>();
        for (int step = 0; step < 10; step++) {
            Requests.Step next = requests.next(bag);
            next.use().ifPresent(used::add);
            finished.addAll(next.finishedWith());
        }

        assertEquals(List.of(2010007, 2010007), used, "twice, as asked, and no more");
        assertEquals(List.of(2000), finished);
        assertFalse(requests.waitingOn(2000));
    }

    @Test
    void givesUpWhenThereIsNoneLeftToUse() {
        Requests requests = new Requests();
        requests.use(2000, 2010007, 1);
        assertTrue(requests.waitingOn(2000));

        Requests.Step step = requests.next(carrying(2000000, 1));

        assertEquals(Optional.empty(), step.use());
        assertEquals(List.of(2000), step.finishedWith());
    }

    private static Inventory carrying(int itemId, int count) {
        Inventory bag = new Inventory();
        bag.update(new Observation.InventoryShown(0, 100, java.util.Map.of(Item.USE, 24),
                List.of(new Item(Item.USE, 1, itemId, count, null))));
        return bag;
    }
}
