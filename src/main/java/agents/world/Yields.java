package agents.world;

import agents.memory.Belief;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much experience a minute each hunting ground gives, at about the agent's level.
 *
 * <p>A hunting ground used to be one fact - something alive was seen there - so a snail and a
 * drake were the same reason to go somewhere. Four agents between levels 44 and 50 made 40
 * thousand experience in an hour and a half between them, hunting pigs and sleepy mushrooms
 * worth a hundred and fifty a kill, while holding beliefs that drakes and lupins give up to
 * thirteen hundred. Nothing that chose where to go asked what a place was worth.
 *
 * <p>So it is measured. Where the agent has hunted, what it actually earned there a minute -
 * deaths, getting its breath back and walking about included - written down as
 * {@code map:M earns_per_minute LEVEL:RATE}. Where it has not, an estimate: what its monsters
 * are known to give a kill, at the rate this agent turns kills into minutes. What a monster
 * gives is something the agent saw in its own experience bar, or was told by somebody who
 * did; nothing here reads the game's monster data, which no player is shown.
 *
 * <p>Measured beats estimated, because the estimate cannot know what kills the agent or how
 * long a kill takes. A drake is worth nine pigs and has killed agents at 41 and 42; only the
 * measurement says whether that is a good trade.
 */
public final class Yields {

    private Yields() {
    }

    /** The predicate a measurement is written under. */
    public static final String EARNS_PER_MINUTE = "earns_per_minute";

    /** Measurements this many levels either side of the agent's still say something about it. */
    static final int NEAR_LEVELS = 3;

    /**
     * Kills a minute assumed before the agent has measured anywhere with monsters whose worth
     * it knows. About what an agent in the forties managed, all its other business included.
     */
    static final double DEFAULT_KILLS_PER_MINUTE = 1.5;

    /**
     * How much an estimate is believed against a measurement. An estimate has not been
     * through a single death or a single long fight, so it errs high, and a measurement of
     * the place next door should not be outbid by a guess about the place itself.
     */
    static final double ESTIMATE_TRUST = 0.7;

    /** The most an estimate may promise, as a multiple of the best it has measured at this level. */
    static final double ESTIMATE_CEILING = 3;

    /** One measurement, as the object of an {@link #EARNS_PER_MINUTE} belief. */
    public static String measurement(int level, double perMinute) {
        return level + ":" + Math.round(perMinute);
    }

    /**
     * Experience a minute the agent can expect in each map it knows something about, at its
     * level: measured where it has been measured, estimated from its monsters elsewhere.
     */
    public static Map<String, Double> expected(List<Belief> beliefs, int level) {
        Map<String, Double> measured = measured(beliefs, level);
        // Nothing measured at about this level, nothing to estimate against. A generation born
        // knowing what drakes give, at level one on Maple Island, rated the snails beside it at
        // a tenth of a hunting ground and walked from map to map for half an hour, never long
        // enough anywhere to measure anything.
        if (measured.isEmpty()) {
            return measured;
        }
        Map<String, Double> perKill = perKill(beliefs);
        Map<String, Set<String>> monstersIn = new HashMap<>();
        for (Belief belief : beliefs) {
            if (belief.predicate().equals("present_in") && belief.subject().startsWith("monster:")) {
                monstersIn.computeIfAbsent(belief.object(), m -> new HashSet<>()).add(belief.subject());
            }
        }
        Map<String, Double> worthAKill = new HashMap<>();
        monstersIn.forEach((map, monsters) -> {
            List<Double> known = monsters.stream().filter(perKill::containsKey).map(perKill::get).toList();
            if (!known.isEmpty()) {
                // The average, not the best: the agent fights whatever is nearest, and a map
                // with one drake among a crowd of pigs is a map of pigs.
                worthAKill.put(map, known.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            }
        });
        double killsPerMinute = killsPerMinute(measured, worthAKill);
        // And never more than a few times the best it has managed: what a monster gives says
        // nothing about whether this agent can kill it, and the more it gives the less likely.
        double ceiling = Collections.max(measured.values()) * ESTIMATE_CEILING;
        Map<String, Double> expected = new HashMap<>();
        worthAKill.forEach((map, exp) -> expected.put(map,
                Math.min(ceiling, exp * killsPerMinute * ESTIMATE_TRUST)));
        expected.putAll(measured);
        return expected;
    }

    /** Only the maps it has measured itself, at about this level. */
    public static Map<String, Double> measured(List<Belief> beliefs, int level) {
        Map<String, double[]> sums = new HashMap<>();
        for (Belief belief : beliefs) {
            if (!belief.predicate().equals(EARNS_PER_MINUTE)) {
                continue;
            }
            String[] parts = belief.object().split(":");
            if (parts.length != 2) {
                continue;
            }
            try {
                int at = Integer.parseInt(parts[0]);
                double rate = Double.parseDouble(parts[1]);
                if (Math.abs(at - level) > NEAR_LEVELS) {
                    continue;
                }
                double[] sum = sums.computeIfAbsent(belief.subject(), m -> new double[2]);
                sum[0] += rate;
                sum[1]++;
            } catch (NumberFormatException ignored) {
                // not a measurement
            }
        }
        Map<String, Double> rates = new HashMap<>();
        sums.forEach((map, sum) -> rates.put(map, sum[0] / sum[1]));
        return rates;
    }

    /**
     * What each monster gives a kill: the amount seen most often, and the middle one of those
     * if several were seen as often.
     *
     * Not the largest. Experience shared with whoever else was hitting, and a kill credited
     * to the wrong death, leave a monster with a spread of amounts - one agent held 27 for the
     * Evil Eye, from 8 to 1500 - and the largest is the one least likely to be what it gives.
     */
    static Map<String, Double> perKill(List<Belief> beliefs) {
        Map<String, Map<Double, Integer>> seen = new HashMap<>();
        for (Belief belief : beliefs) {
            if (!belief.predicate().equals("gives_exp") || !belief.subject().startsWith("monster:")) {
                continue;
            }
            try {
                double amount = Double.parseDouble(belief.object());
                if (amount > 0) {
                    seen.computeIfAbsent(belief.subject(), m -> new HashMap<>())
                            .merge(amount, belief.supportedBy().size(), Integer::sum);
                }
            } catch (NumberFormatException ignored) {
                // not an amount
            }
        }
        Map<String, Double> worth = new HashMap<>();
        seen.forEach((monster, amounts) -> {
            int most = Collections.max(amounts.values());
            List<Double> commonest = new ArrayList<>(amounts.entrySet().stream()
                    .filter(e -> e.getValue() == most).map(Map.Entry::getKey).toList());
            Collections.sort(commonest);
            worth.put(monster, commonest.get((commonest.size() - 1) / 2));
        });
        return worth;
    }

    /**
     * How this agent turns kills into minutes: from the places it has measured whose monsters
     * it knows the worth of, the middle of what it managed; until then, a default.
     */
    static double killsPerMinute(Map<String, Double> measured, Map<String, Double> worthAKill) {
        List<Double> rates = new ArrayList<>();
        measured.forEach((map, rate) -> {
            Double kill = worthAKill.get(map);
            if (kill != null && kill > 0 && rate > 0) {
                rates.add(rate / kill);
            }
        });
        if (rates.isEmpty()) {
            return DEFAULT_KILLS_PER_MINUTE;
        }
        Collections.sort(rates);
        return rates.get(rates.size() / 2);
    }
}
