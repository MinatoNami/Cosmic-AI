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
                          double errand, double shop, double hunting, double neverBeen,
                          double novelty, double perHop, double spentRoom, double justBeenFor,
                          double danger) {
        // justBeenFor is a share, not an amount: 1 wipes out a place's reasons on arrival.

        public Weights(double unopenedDoor, double stranger, double worthHearingAgain,
                       double errand, double shop, double hunting, double neverBeen,
                       double novelty, double perHop, double spentRoom, double justBeenFor) {
            this(unopenedDoor, stranger, worthHearingAgain, errand, shop, hunting, neverBeen,
                    novelty, perHop, spentRoom, justBeenFor, 2.0);
        }
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
     * @param shopMaps     where somebody who buys and sells stands, when the agent has
     *                     reason to see one - a full bag, or nothing left that heals - and
     *                     empty otherwise
     * @param level        the agent's level, or 0 when it does not know it
     * @param huntedAt     the level people were seen hunting each map at, from recordings of
     *                     them playing - empty when nobody has been watched
     * @param expRate      experience a minute it can expect in each map, at its level (see
     *                     {@link Yields}) - empty when it knows nothing of what anything gives
     * @param rateHere     what a hunting ground has to beat: what it earns where it is, or 0
     *                     when that is not known
     */
    public record Facts(String here, Map<String, Integer> visits, String errandMap,
                        Set<String> hearAgain, Set<String> avoid, Map<String, Double> justBeenFor,
                        Set<String> shopMaps, String sentTo, String sentWhy,
                        Map<String, Integer> deaths, int level, Map<String, Integer> huntedAt,
                        Map<String, Double> expRate, double rateHere) {

        public Facts(String here, Map<String, Integer> visits, String errandMap,
                     Set<String> hearAgain, Set<String> avoid, Map<String, Double> justBeenFor,
                     Set<String> shopMaps, String sentTo, String sentWhy,
                     Map<String, Integer> deaths, int level, Map<String, Integer> huntedAt) {
            this(here, visits, errandMap, hearAgain, avoid, justBeenFor, shopMaps, sentTo, sentWhy,
                    deaths, level, huntedAt, Map.of(), 0);
        }

        public Facts(String here, Map<String, Integer> visits, String errandMap,
                     Set<String> hearAgain, Set<String> avoid, Map<String, Double> justBeenFor,
                     Set<String> shopMaps, String sentTo, String sentWhy,
                     Map<String, Integer> deaths) {
            this(here, visits, errandMap, hearAgain, avoid, justBeenFor, shopMaps, sentTo, sentWhy,
                    deaths, 0, Map.of());
        }

        public Facts(String here, Map<String, Integer> visits, String errandMap,
                     Set<String> hearAgain, Set<String> avoid, Map<String, Double> justBeenFor,
                     Set<String> shopMaps) {
            this(here, visits, errandMap, hearAgain, avoid, justBeenFor, shopMaps, null, null, Map.of());
        }

        public Facts(String here, Map<String, Integer> visits, String errandMap,
                     Set<String> hearAgain, Set<String> avoid, Map<String, Double> justBeenFor,
                     Set<String> shopMaps, String sentTo, String sentWhy) {
            this(here, visits, errandMap, hearAgain, avoid, justBeenFor, shopMaps, sentTo, sentWhy, Map.of());
        }
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
        return worthGoing(known, facts, w, true);
    }

    /**
     * @param throughDangerIfNeeded whether places beyond a killing ground may be offered when
     *                              nothing else is - not straight after dying in one
     */
    public static List<Place> worthGoing(KnownWorld known, Facts facts, Weights w,
                                         boolean throughDangerIfNeeded) {
        Set<String> grounds = killingGrounds(facts);
        List<Place> places = worthGoing(known, facts, w, grounds, false);
        // What it must do is worth the danger: a Bowman with no bow, whose only bow-seller was
        // beyond the dungeon that had killed it, hunted with a spear in the dungeon instead.
        Set<String> needed = new java.util.HashSet<>(facts.shopMaps());
        if (facts.errandMap() != null) {
            needed.add(facts.errandMap());
        }
        if (facts.sentTo() != null) {
            needed.add(facts.sentTo());
        }
        // Not a killing ground itself - except where it was sent. Agent0 was born believing the
        // Road to the Dungeon had killed a predecessor at 37 and been too hard at 42, so the
        // map where Athena Pierce's letter had to go was a killing ground, and the one place
        // the second job waited was never once offered.
        grounds.stream().filter(ground -> !ground.equals(facts.sentTo())).forEach(needed::remove);
        places.stream().map(Place::map).toList().forEach(needed::remove);
        // Whenever there is somewhere it must go, not only after recovering from dying: a Warrior
        // due its second job could reach its trainer only through maps it had died in, and
        // with the way not offered it explored around Henesys instead, for hours.
        if (!needed.isEmpty() && !grounds.isEmpty()) {
            List<Place> withDanger = new ArrayList<>(places);
            worthGoing(known, facts, w, Set.of(), true).stream()
                    .filter(place -> needed.contains(place.map()))
                    .forEach(withDanger::add);
            withDanger.sort(Comparator.comparingDouble(Place::value).reversed()
                    .thenComparingInt(Place::hops).thenComparing(Place::map));
            places = withDanger;
        }
        // And what it must do comes before what it would like to: a Bowman with no bow, one map
        // out of Sleepywood on its way to the bow shop, turned back for Kerning City because
        // twelve unopened doors there outscored the shop.
        Set<String> musts = new java.util.HashSet<>(facts.shopMaps());
        if (facts.errandMap() != null) {
            musts.add(facts.errandMap());
        }
        if (facts.sentTo() != null) {
            musts.add(facts.sentTo());
        }
        if (!musts.isEmpty()) {
            List<Place> ordered = new ArrayList<>(places);
            ordered.sort(Comparator.comparingInt((Place place) -> musts.contains(place.map()) ? 0 : 1));
            places = ordered;
        }
        if (places.isEmpty() && !grounds.isEmpty() && throughDangerIfNeeded) {
            // Walled in by places that killed it. Sleepywood's only ways out on foot are the
            // dungeons either side, where predecessors died; avoiding both left nowhere worth
            // walking to, so the only things that moved the agents were NPCs offering
            // passage - which brought them back to Sleepywood, for eight hours. Going through
            // is better than that. Not to them: the grounds themselves are still not offered.
            places = worthGoing(known, facts, w, Set.of(), true).stream()
                    .filter(place -> !grounds.contains(place.map()))
                    .toList();
        }
        return places;
    }

    private static List<Place> worthGoing(KnownWorld known, Facts facts, Weights w,
                                          Set<String> avoiding, boolean throughDanger) {
        List<Place> places = new ArrayList<>();
        double rateToBeat = facts.rateHere() > 0 ? facts.rateHere() : typicalRate(facts.expRate());
        for (Map.Entry<String, KnownWorld.Hop> entry
                : known.reachableFrom(facts.here(), avoiding).entrySet()) {
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
            if (facts.shopMaps().contains(map)) {
                value += w.shop();
                reasons.add("somebody who buys and sells, and you need to");
            }
            Integer peopleHuntAt = facts.level() > 0 ? facts.huntedAt().get(map) : null;
            if (peopleHuntAt != null && peopleHuntAt - facts.level() > TOO_STRONG_BY) {
                // Somewhere people only hunt once they are much stronger is not a hunting
                // ground yet, whatever lives there: it is the next place to die.
                value -= w.hunting();
                reasons.add("people hunt there from level " + peopleHuntAt);
            } else if (known.huntingIn(map) || peopleHuntAt != null) {
                double hunting = w.hunting();
                if (peopleHuntAt != null && facts.level() - peopleHuntAt > OUTGROWN_BY) {
                    hunting /= 2;
                    reasons.add("people outgrow it by your level");
                } else if (peopleHuntAt != null) {
                    // People train there at about this level: the one thing about a hunting
                    // ground that seeing a snail in it cannot tell you.
                    hunting *= 2;
                    reasons.add("people hunt there at about your level");
                }
                // What the hunting there is worth a minute, against what it has now. Seeing
                // something alive made a field of pigs as good a reason as a drake's cave, and
                // agents in their forties hunted pigs for hours.
                Double rate = facts.expRate().get(map);
                if (rate != null && rateToBeat > 0) {
                    hunting *= worthOfRate(rate, rateToBeat);
                    reasons.add("about " + Math.round(rate) + " experience a minute"
                            + (facts.rateHere() > 0 ? ", against " + Math.round(facts.rateHere()) + " here" : ""));
                }
                value += hunting;
                if (known.huntingIn(map)) {
                    reasons.add("something living you have seen there");
                }
            }
            int visits = facts.visits().getOrDefault(map, 0);
            if (visits == 0) {
                value += w.neverBeen();
                reasons.add("you have never been there");
            }
            boolean sent = map.equals(facts.sentTo());
            if (reasons.isEmpty() && !sent) {
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
            // Being sent somewhere is not scaled down for having been there lately: it is
            // dropped the moment the agent arrives, so it cannot send it round in a loop, and a
            // second job is a string of trips to places it has just come from.
            if (sent) {
                value += w.errand();
                reasons.add(0, facts.sentWhy() == null ? "somewhere you were sent" : facts.sentWhy());
            }
            // Somewhere that killed it at about this level is somewhere to stay out of. Two
            // deaths outweigh most reasons to go, so it is not even offered; as the agent grows
            // past the level it died at, the deaths stop counting.
            int died = facts.deaths().getOrDefault(map, 0);
            if (died > 0) {
                value -= w.danger() * died;
                reasons.add("you died there " + (died == 1 ? "once" : died + " times"));
            }
            value += w.novelty() / Math.sqrt(1 + visits);
            value -= w.perHop() * hop.hops();
            if (known.isSpentRoom(map)) {
                value -= w.spentRoom();
            }
            if (visits > 0) {
                reasons.add("been " + visits + (visits == 1 ? " time" : " times"));
            }
            // The walk costing more than there is to find rules a place out - unless it is
            // somewhere it must go. A Warrior's trainer, fourteen maps away, scored below nothing
            // for the distance, was never offered, and its second job waited for hours.
            // Only where it is sent: an errand or a shop just come back from is meant to drop out
            // for a while, or it heads straight back for the thing it just went there for.
            boolean must = sent;
            if (value <= 0 && !must) {
                continue;       // the walk costs more than there is to find
            }
            if (must) {
                value = Math.max(value, MUST_FLOOR);
            }
            if (throughDanger) {
                reasons.add("the only way on is through somewhere that killed you");
            }
            places.add(new Place(map, hop.hops(), hop.firstDoor(), value, List.copyOf(reasons)));
        }
        places.sort(Comparator.comparingDouble(Place::value).reversed()
                .thenComparingInt(Place::hops)
                .thenComparing(Place::map));
        return places;
    }

    /** Maps that have killed the agent twice or more at about its level: not to pass through. */
    public static Set<String> killingGrounds(Facts facts) {
        Set<String> grounds = new java.util.HashSet<>();
        facts.deaths().forEach((map, died) -> {
            if (died >= KILLING_GROUND && !map.equals(facts.here())) {
                grounds.add(map);
            }
        });
        return grounds;
    }

    public static final int KILLING_GROUND = 2;

    /**
     * How much a hunting ground's usual worth is scaled for what it gives a minute against what
     * it has to beat: twice as good is worth twice, four times as good three times, half as
     * good next to nothing. By doublings rather than the plain ratio, so a cave worth ten
     * times the field does not outweigh an errand, a death or the walk.
     */
    static double worthOfRate(double rate, double toBeat) {
        if (rate <= 0) {
            return RATE_FLOOR;
        }
        double doublings = Math.log(rate / toBeat) / Math.log(2);
        return Math.max(RATE_FLOOR, Math.min(RATE_CEILING, 1 + doublings));
    }

    static final double RATE_FLOOR = 0.1;
    static final double RATE_CEILING = 3;

    /**
     * What a hunting ground has to beat when what it earns here is not known - in a town, or
     * just arrived: the middle of what it expects anywhere, so the better half of what it knows
     * pulls and the worse half does not.
     */
    static double typicalRate(Map<String, Double> rates) {
        if (rates.isEmpty()) {
            return 0;
        }
        List<Double> sorted = rates.values().stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    /** Levels below where people hunt a map at which it is too soon to go. */
    static final int TOO_STRONG_BY = 5;

    /** The least a place it must go to is worth, however far. */
    static final double MUST_FLOOR = 0.01;

    /** Levels above where people hunt a map at which it is worth less than it was. */
    static final int OUTGROWN_BY = 10;

    private static Set<String> withoutAvoided(Set<String> people, Set<String> avoid) {
        if (avoid.isEmpty()) {
            return people;
        }
        Set<String> kept = new java.util.HashSet<>(people);
        kept.removeAll(avoid);
        return kept;
    }
}
