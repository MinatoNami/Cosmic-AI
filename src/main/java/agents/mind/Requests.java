package agents.mind;

import agents.world.Inventory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Things an NPC asked the agent to do with what it is carrying, done one at a time.
 *
 * <p>Using an item is the one kind of request nothing else in the agent would ever get round
 * to: going somewhere, finding somebody and bringing things back already have reflexes that
 * act on what was heard. Eating an apple because Roger said to does not - the agent only ever
 * ate when it was hurt - so it is done here, as soon as it is asked, a use every other step,
 * the way a player double-clicks through a stack.
 *
 * <p>A request ends when it has been done as often as asked, or when there is none of the
 * thing left to use. Either way the NPC who asked is reported finished with, so whoever was
 * holding back from handing the quest in can stop waiting.
 */
public final class Requests {

    private record Use(int npcId, int itemId, int remaining) {
    }

    private final Deque<Use> uses = new ArrayDeque<>();
    private int sinceLast;

    /** Uses at most every other step, so the server sees each one land before the next. */
    private static final int STEPS_BETWEEN_USES = 2;

    public void use(int npcId, int itemId, int times) {
        uses.addLast(new Use(npcId, itemId, times));
    }

    public boolean waitingOn(int npcId) {
        return uses.stream().anyMatch(use -> use.npcId() == npcId);
    }

    /** What happened this step: something to use now, and anybody now finished with. */
    public record Step(Optional<Integer> use, List<Integer> finishedWith) {
    }

    public Step next(Inventory inventory) {
        List<Integer> finished = new ArrayList<>();
        // Anything gone from the bag is as done as it will get.
        while (!uses.isEmpty() && inventory.count(uses.peekFirst().itemId()) == 0) {
            finishedIfLast(uses.removeFirst(), finished);
        }
        if (uses.isEmpty() || ++sinceLast < STEPS_BETWEEN_USES) {
            return new Step(Optional.empty(), finished);
        }
        sinceLast = 0;
        Use head = uses.removeFirst();
        if (head.remaining() > 1) {
            uses.addFirst(new Use(head.npcId(), head.itemId(), head.remaining() - 1));
        } else {
            finishedIfLast(head, finished);
        }
        return new Step(Optional.of(head.itemId()), finished);
    }

    private void finishedIfLast(Use done, List<Integer> finished) {
        if (!waitingOn(done.npcId())) {
            finished.add(done.npcId());
        }
    }
}
