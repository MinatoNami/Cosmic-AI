package agents.mind;

import java.util.HashMap;
import java.util.Map;

/**
 * Offers the agent has turned down, and whether turning them down has stopped making sense.
 *
 * <p>An agent says no to being taken somewhere while it still has somewhere of its own to go,
 * which is right on the first morning and wrong forever after. Maple Island always has some
 * door the agent can still reach and has never opened, so by that rule the island is never
 * finished: Agent2 was offered the ferry to Victoria Island ninety-four times, at level 34
 * with 1.3 million mesos, and turned it down every time - five maps for six hundred thousand
 * ticks, and never so much as saw the person who would have given it a job.
 *
 * <p>The question the rule was standing in for is whether staying is still teaching the agent
 * anything, and that it can answer from its own memory: when it last found something out. An
 * offer it has refused {@value #ENOUGH} times, from somewhere that has taught it nothing new
 * since the first refusal, is no longer a distraction from anything. It is the way on.
 */
public final class Refusals {

    /** Turning something down this often, learning nothing in between, is a habit, not a choice. */
    static final int ENOUGH = 3;

    private record Count(int times, long firstAt) {
    }

    private final Map<Integer, Count> declined = new HashMap<>();

    /**
     * The agent said no to this one. Refusals before the last time it found something out do
     * not count: that discovery was a reason to stay, so the count starts again after it.
     */
    public void declined(int npcId, long tick, long lastNoveltyTick) {
        Count was = declined.get(npcId);
        boolean stillCounting = was != null && was.firstAt() > lastNoveltyTick;
        declined.put(npcId, stillCounting ? new Count(was.times() + 1, was.firstAt()) : new Count(1, tick));
    }

    /** The agent said yes, so whatever it was refusing is behind it. */
    public void accepted(int npcId) {
        declined.remove(npcId);
    }

    /**
     * Whether refusing this one again would be refusing out of habit: turned down often
     * enough, with nothing found out since the first time.
     *
     * @param lastNoveltyTick when the agent last learnt something it did not know, or -1
     */
    public boolean outgrown(int npcId, long lastNoveltyTick) {
        Count count = declined.get(npcId);
        return count != null && count.times() >= ENOUGH && lastNoveltyTick < count.firstAt();
    }

    public int timesDeclined(int npcId) {
        Count count = declined.get(npcId);
        return count == null ? 0 : count.times();
    }
}
