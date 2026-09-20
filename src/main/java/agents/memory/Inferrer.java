package agents.memory;

import agents.percept.Observation;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Things an agent works out for itself from what it has seen.
 *
 * {@link BeliefFormer} restates and never concludes, deliberately, and there is a test pinning
 * that restraint: an observation saying a monster is here becomes a belief that a monster is
 * here, and nothing more. That leaves the interesting half of the project unbuilt, because the
 * conclusions worth watching an agent reach - that killing these things produces those things -
 * are exactly the ones no packet ever states.
 *
 * <p>This is where those live, kept apart from restatement so the two can never be confused in
 * a replay. Everything here comes out as {@link Belief.Provenance#INFERRED}, which the
 * confidence curve already scores below a sighting, because a conclusion drawn from two
 * coincidences can be wrong in ways a sighting cannot.
 *
 * <p>The one rule it knows: things that fall where something just died probably came from it.
 * That is a guess a person makes in the first minute of playing, from the same evidence, and it
 * is wrong often enough to be worth holding at low confidence and revising.
 */
public class Inferrer {

    /**
     * How long after a death a drop can still be credited to it.
     *
     * Ticks are packets received, not time, so this is deliberately short: anything further
     * back and an agent in a crowded map credits whatever died first.
     */
    private static final int WITHIN_TICKS = 12;

    /** How close the drop has to land. A monster's loot falls where it stood. */
    private static final int WITHIN_PIXELS = 220;

    /**
     * How many times it has to happen before the agent will say so.
     *
     * One co-occurrence in a busy map is a coincidence. Two is the least that can be called
     * noticing, and the confidence curve keeps even that modest.
     */
    private static final int SEEN_TOGETHER = 2;

    /** A monster seen dying, and where it was standing when it did. */
    private record Death(int monsterId, long tick, Point where) {
    }

    private final Map<Integer, Integer> typeByObjectId = new HashMap<>();
    private final Map<Integer, Point> lastSeenAt = new HashMap<>();
    private final List<Death> deaths = new ArrayList<>();
    private final Map<String, Integer> timesSeenTogether = new HashMap<>();
    private final List<String> alreadySaid = new ArrayList<>();

    /** Something the agent has concluded, in the same shape a belief takes. */
    public record Conclusion(String subject, String predicate, String object) {
    }

    /**
     * Files an observation and says what, if anything, it now suspects.
     *
     * @return conclusions worth asserting, usually none
     */
    public List<Conclusion> consider(Observation observation) {
        switch (observation) {
            case Observation.MonsterAppeared monster -> {
                typeByObjectId.put(monster.objectId(), monster.monsterId());
                lastSeenAt.put(monster.objectId(), monster.position());
            }
            case Observation.ThingMoved moved -> {
                if (typeByObjectId.containsKey(moved.objectId())) {
                    lastSeenAt.put(moved.objectId(), moved.position());
                }
            }
            case Observation.MonsterDied died -> {
                Integer type = typeByObjectId.get(died.objectId());
                Point where = lastSeenAt.get(died.objectId());
                if (type != null && where != null) {
                    deaths.add(new Death(type, died.tick(), where));
                }
                typeByObjectId.remove(died.objectId());
                lastSeenAt.remove(died.objectId());
            }
            case Observation.DropAppeared drop -> {
                return creditFor(drop);
            }
            default -> {
                // Nothing here concludes anything from the rest, which is most of them.
            }
        }
        return List.of();
    }

    private List<Conclusion> creditFor(Observation.DropAppeared drop) {
        forget(drop.tick());
        Optional<Death> culprit = deaths.stream()
                .filter(death -> drop.tick() - death.tick() <= WITHIN_TICKS)
                .filter(death -> death.where().distance(drop.position()) <= WITHIN_PIXELS)
                .reduce((first, second) -> second);     // the most recent one that fits
        if (culprit.isEmpty()) {
            return List.of();
        }

        String subject = "monster:" + culprit.get().monsterId();
        String object = drop.meso() ? "meso" : "item:" + drop.itemId();
        String pair = subject + "|" + object;

        int together = timesSeenTogether.merge(pair, 1, Integer::sum);
        if (together < SEEN_TOGETHER || alreadySaid.contains(pair)) {
            return List.of();
        }
        alreadySaid.add(pair);
        return List.of(new Conclusion(subject, "drops", object));
    }

    /** Deaths too old to have produced anything falling now. */
    private void forget(long now) {
        deaths.removeIf(death -> now - death.tick() > WITHIN_TICKS);
    }
}
