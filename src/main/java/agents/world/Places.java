package agents.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every place an agent knows how to reach, each with one number saying how much it is worth
 * going there.
 *
 * <p>This replaces a chain of questions asked one at a time - is there an errand, then an
 * unopened door, then a stranger, then unfinished talk, then monsters - taking the first yes.
 * Each link was a sensible rule and together they looped: an agent in Southperry took the
 * errand rule to Split Road; in Split Road, where the errand was, that rule had nothing to
 * say, so the stranger rule sent it back to Southperry; where, with a stranger in sight, that
 * rule had nothing to say, so the errand rule sent it to Split Road. Nearly five hundred door
 * decisions in five minutes. Every map asked a different question, and no answer remembered
 * the last one.
 *
 * <p>Here every reason to go somewhere is a term in the same sum, so a place with an errand
 * and a place with a stranger are compared rather than taking turns, and going somewhere and
 * finding nothing there makes it worth less next time. That second part is the idea of
 * Go-Explore's archive and of count-based exploration: somewhere you have been often, or just
 * came back from, is worth less than somewhere you have not.
 *
 * <p>Everything scored is something the agent itself has seen or been told. No map data.
 */
public final class Places {

    private Places() {
    }

    /**
     * How much each reason to go somewhere is worth, set per disposition, and how much each
     * door of walking costs.
     */
    public record Weights(double unopenedDoor, double stranger, double worthHearingAgain,
                          double errand, double hunting, double neverBeen, double novelty,
                          double perHop, double spentRoom, double justBeenFor) {
        // justBeenFor is a share, not an amount: 1 wipes out a place's reasons on arrival.
    }

    /**
     * What the agent knows that is not in the map graph.
     *
     * @param visits       how many times it has walked into each map
     * @param errandMap    where somebody is waiting whose price it can now pay, or null
     * @param hearAgain    people worth another conversation
     * @param avoid        people it will not go to, whatever they are holding
     * @param justBeenFor  how recently each map was a destination it reached, from 1 (just
     *                     now) falling to 0 - a trip that has just been made is not worth
     *                     making again straight away
     */
    public record Facts(String here, Map<String, Integer> visits, String errandMap,
                        Set<String> hearAgain, Set<String> avoid, Map<String, Double> justBeenFor) {
    }

    /** A place worth considering, why, and the first door towards it. */
    public record Place(String map, int hops, String firstDoor, double value, List<String> reasons) {

        /** One line, in ids, for a model to read. */
        public String describe() {
            return map + ", " + hops + (hops == 1 ? " door" : " doors") + " away: "
                    + String.join(", ", reasons);
        }
    }

    /**
     * The places worth going to, best first.
     *
     * Only places with a concrete reason, and worth more than the walk, are returned - something unopened, somebody to see,
     * somewhere never stood in, something to hunt. Being far away and rarely visited is a
     * tie-break between reasons, not a reason on its own, or an agent with nothing to do
     * would set off for the least-visited corridor in its map.
     */
    public static List<Place> worthGoing(KnownWorld known, Facts facts, Weights w) {
        List<Place> places = new ArrayList<>();
        for (Map.Entry<String, KnownWorld.Hop> entry : known.reachableFrom(facts.here()).entrySet()) {
            String map = entry.getKey();
            KnownWorld.Hop hop = entry.getValue();
            if (hop.hops() == 0) {
                continue;       // what is here is for the other reflexes; this is about leaving
            }

            List<String> reasons = new ArrayList<>();
            double value = 0;

            int unopened = known.unopenedDoorsIn(map).size();
            if (unopened > 0) {
                value += w.unopenedDoor() * Math.sqrt(unopened);
                reasons.add(unopened == 1 ? "1 door you have never opened"
                        : unopened + " doors you have never opened");
            }
            int strangers = known.strangersIn(map, facts.avoid());
            if (strangers > 0) {
                value += w.stranger() * Math.sqrt(strangers);
                reasons.add(strangers == 1 ? "somebody you have never spoken to"
                        : strangers + " people you have never spoken to");
            }
            if (map.equals(facts.errandMap())) {
                value += w.errand();
                reasons.add("somebody who asked for something you now have");
            } else if (known.anyOfIn(map, withoutAvoided(facts.hearAgain(), facts.avoid()))) {
                value += w.worthHearingAgain();
                reasons.add("somebody worth talking to again");
            }
            if (known.huntingIn(map)) {
                value += w.hunting();
                reasons.add("something living you have seen there");
            }
            int visits = facts.visits().getOrDefault(map, 0);
            if (visits == 0) {
                value += w.neverBeen();
                reasons.add("you have never been there");
            }
            if (reasons.isEmpty()) {
                continue;
            }

            // Having just been somewhere for its reasons scales all of them down together,
            // rather than subtracting a fixed amount. A fixed amount was outvoted by a place
            // with two reasons - an errand and the stranger who was the errand - and the agent
            // went straight back for the thing it had just gone there for.
            double justBeen = facts.justBeenFor().getOrDefault(map, 0.0);
            if (justBeen > 0) {
                value *= Math.max(0, 1 - w.justBeenFor() * justBeen);
                reasons.add("you went there recently for this");
            }
            value += w.novelty() / Math.sqrt(1 + visits);
            value -= w.perHop() * hop.hops();
            if (known.isSpentRoom(map)) {
                value -= w.spentRoom();
            }
            if (visits > 0) {
                reasons.add("been " + visits + (visits == 1 ? " time" : " times"));
            }
            if (value <= 0) {
                continue;       // the walk costs more than there is to find
            }
            places.add(new Place(map, hop.hops(), hop.firstDoor(), value, List.copyOf(reasons)));
        }
        places.sort(Comparator.comparingDouble(Place::value).reversed()
                .thenComparingInt(Place::hops)
                .thenComparing(Place::map));
        return places;
    }

    private static Set<String> withoutAvoided(Set<String> people, Set<String> avoid) {
        if (avoid.isEmpty()) {
            return people;
        }
        Set<String> kept = new java.util.HashSet<>(people);
        kept.removeAll(avoid);
        return kept;
    }
}
