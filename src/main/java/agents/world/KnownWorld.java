package agents.world;

import agents.memory.Belief;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The shape of the world as one agent remembers it.
 *
 * Built entirely from that agent's own beliefs: doors it has stood in front of, and where
 * the ones it walked through turned out to lead. Nothing here is read from the game files,
 * so an agent's map of the world is exactly as good as the walking it has done - which is
 * the same rule {@link MapGeometry} follows when it refuses to read a portal's target.
 *
 * <p>This exists because choosing a door by looking only at the room you are standing in
 * cannot express the one thing an explorer most needs to think: <em>there is a door I never
 * opened, two maps west of here.</em> An agent ran for hours inside nine maps whose every
 * known exit led somewhere it had already been - including one map a single door from the
 * way off the island, which it had visited and left by the way it came in. Every exit was a
 * sensible local choice. The trip it needed to make was three doors long, and no ranking of
 * the doors in the current room can see that far.
 */
public final class KnownWorld {

    /** Where a door goes when walking into it does nothing at all. */
    public static final String NOWHERE = "nowhere";

    /** Doors that led somewhere: source map -> door name -> destination map. */
    private final Map<String, Map<String, String>> exits = new HashMap<>();

    /** Doors the agent remembers seeing: map -> door names. */
    private final Map<String, Set<String>> doorsSeen = new HashMap<>();

    /** Doors it has reached a verdict on, good or bad, by portal ref. */
    private final Set<String> settled = new HashSet<>();

    /** Maps something was seen living in, which is where an agent can expect a fight. */
    private final Set<String> hunting = new HashSet<>();

    /** Who is standing where, and which of them this agent has actually spoken to. */
    private final Map<String, Set<String>> peopleIn = new HashMap<>();
    private final Set<String> spokenTo = new HashSet<>();

    /** Those who stated a condition, so the agent already has what they had to give. */
    private final Set<String> namedAPrice = new HashSet<>();

    private KnownWorld() {
    }

    /**
     * Reads the graph out of live beliefs.
     *
     * Live only: an invalidated {@code leads_to} is a door whose destination the agent has
     * since changed its mind about, and routing through it would be routing through
     * something it no longer believes.
     */
    public static KnownWorld rememberedBy(List<Belief> beliefs) {
        KnownWorld world = new KnownWorld();
        for (Belief belief : beliefs) {
            switch (belief.predicate()) {
                case "has_door" -> world.doorsSeen
                        .computeIfAbsent(belief.subject(), map -> new HashSet<>())
                        .add(belief.object());
                case "present_in" -> {
                    if (belief.subject().startsWith("monster:")) {
                        world.hunting.add(belief.object());
                    } else if (belief.subject().startsWith("npc:")) {
                        world.peopleIn.computeIfAbsent(belief.object(), m -> new HashSet<>())
                                .add(belief.subject());
                    }
                }
                case "wants_first" -> world.namedAPrice.add(belief.subject());
                case "talks_in" -> {
                    // First-hand only. An inherited conversation is one a predecessor had:
                    // this agent has never met them, and going to find out what they say is
                    // exactly the journey worth making.
                    if (belief.provenance() == Belief.Provenance.FIRST_HAND) {
                        world.spokenTo.add(belief.subject());
                    }
                    world.peopleIn.computeIfAbsent(belief.object(), m -> new HashSet<>())
                            .add(belief.subject());
                }
                case "leads_to" -> {
                    String portal = belief.subject();
                    world.settled.add(portal);
                    if (!NOWHERE.equals(belief.object())) {
                        String map = mapOf(portal);
                        String door = doorOf(portal);
                        if (map != null && door != null) {
                            world.exits.computeIfAbsent(map, m -> new TreeMap<>())
                                    .put(door, belief.object());
                        }
                    }
                }
                default -> {
                }
            }
        }
        return world;
    }

    /**
     * Where a door leads, as far as this agent knows.
     *
     * A real destination outranks {@link #NOWHERE} rather than whichever verdict happens to
     * be older. {@code leads_to} is not a functional predicate - a door can hold both
     * verdicts at once - and reading the first one written meant a door that timed out once
     * and worked every time after was written off permanently. Having seen where it goes is
     * evidence; having once failed to notice is not.
     */
    public Optional<String> destinationOf(String portalRef) {
        String map = mapOf(portalRef);
        String door = doorOf(portalRef);
        String destination = map == null ? null : exits.getOrDefault(map, Map.of()).get(door);
        if (destination != null) {
            return Optional.of(destination);
        }
        return settled.contains(portalRef) ? Optional.of(NOWHERE) : Optional.empty();
    }

    /** Doors in this map the agent has seen and never found out the far side of. */
    public Set<String> unopenedDoorsIn(String mapRef) {
        Set<String> unopened = new HashSet<>();
        for (String door : doorsSeen.getOrDefault(mapRef, Set.of())) {
            if (!settled.contains(portalRef(mapRef, door))) {
                unopened.add(door);
            }
        }
        return unopened;
    }

    /**
     * A room the agent has already seen all of: one way out, and nothing left unopened.
     *
     * A shop, a house, a stairwell. Worth knowing about because of what it does to the
     * fallback rank: once a town's own doors have all been opened, every shop entrance is
     * still "a door that works", so an agent would go in, come straight back out, and do it
     * again. One spent hours between Lith Harbor and four of its shops that way. Going back
     * into a room whose single exit you have already used cannot teach you anything.
     *
     * Derived from the doors the agent remembers seeing, so a map it has never entered is
     * not a room - it is simply unknown, and worth a look.
     */
    public boolean isSpentRoom(String mapRef) {
        Set<String> doors = doorsSeen.getOrDefault(mapRef, Set.of());
        return doors.size() == 1 && unopenedDoorsIn(mapRef).isEmpty();
    }

    /** Every map this agent has learned the name of by walking into it. */
    public Set<String> mapsReachable() {
        Set<String> maps = new HashSet<>(exits.keySet());
        exits.values().forEach(byDoor -> maps.addAll(byDoor.values()));
        return maps;
    }

    /**
     * The shortest walk from here to a map with a door in it nobody has opened.
     *
     * This is what "explore" means once an agent has been anywhere at all: not "which of
     * these doors looks promising" but "where is the edge of what I know, and which way is
     * it from here".
     */
    public Optional<Route> routeToNearestFrontier(String fromMap) {
        return search(fromMap, map -> !unopenedDoorsIn(map).isEmpty(), "frontier");
    }

    /**
     * The shortest walk from here to somewhere it remembers something living.
     *
     * The last thing to try, and the answer to a world that has run out of unopened doors.
     * Two agents inherited a map of a hundred and three places and then had nothing to
     * explore: their own island was fully opened by the generation before, and the rest of
     * that map was reached by an NPC warp, which leaves no edge to walk along. So every door
     * fell through to the weakest rule there is, which shuffled them between shops and
     * tutorial rooms - none of which contain monsters. A fighter spent half an hour at 0%
     * fighting and gained no levels.
     *
     * It already held three hundred and twenty-one sightings of monsters in named maps,
     * inherited from the generation that killed them, and nothing whatsoever read them.
     * Somewhere you remember something living is a reason to travel.
     */
    public Optional<Route> routeToMonsters(String fromMap) {
        return search(fromMap, map -> !map.equals(fromMap) && hunting.contains(map), "hunting");
    }

    /**
     * The shortest walk from here to somebody this agent has never spoken to.
     *
     * Ranked above hunting because of what each can change. Another snail is calories; a
     * stranger might be holding the only way off the island - which is exactly the case this
     * was written for. Both agents remembered NPCs in Southperry, had never met the one who
     * sells passage, and had no reason to walk back there: no unopened doors anywhere, and
     * no monsters remembered in that map. They spent half an hour going between two maps.
     *
     * Spoken-to counts only first-hand conversations. An inherited one belonged to a
     * predecessor, and finding out for yourself what somebody says is the whole point.
     */
    public Optional<Route> routeToStrangers(String fromMap) {
        if (hasAStranger(fromMap)) {
            // Somebody new is already here. Walking to the next map to meet a stranger while
            // one stands in front of you is how an agent spent an afternoon in Southperry
            // with three people in sight and Shanks - who sells the only passage off the
            // island - among them, heading for the door the whole time.
            return Optional.empty();
        }
        return search(fromMap, map -> !map.equals(fromMap) && hasAStranger(map), "a stranger");
    }

    private boolean hasAStranger(String mapRef) {
        for (String person : peopleIn.getOrDefault(mapRef, Set.of())) {
            if (!spokenTo.contains(person)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shortest walk to somebody worth hearing from again.
     *
     * There has always been a way back to an NPC that named a price - that is what
     * wants_first is - and no way back to one the agent spoke to and got nothing from. An
     * agent asked Shanks a question during the window when every dialogue was timing out,
     * came away with nothing, and filed him as met; after that nothing in the world would
     * take it back to the one person holding the way off the island, because a met NPC is
     * not a stranger and a map of met NPCs is not worth travelling to.
     *
     * Who is worth hearing again is the caller's judgement, because the cooldown that stops
     * an agent pestering somebody lives with the policy. This only knows where they stand.
     */
    public Optional<Route> routeToUnfinishedTalk(String fromMap, Set<String> worthHearingAgain) {
        if (worthHearingAgain.isEmpty()) {
            return Optional.empty();
        }
        return search(fromMap, map -> !map.equals(fromMap)
                        && peopleIn.getOrDefault(map, Set.of()).stream()
                                .anyMatch(worthHearingAgain::contains),
                "unfinished talk");
    }

    /** Everyone this agent has heard speak, whether or not it learned anything. */
    public Set<String> everyoneSpokenTo() {
        return Set.copyOf(spokenTo);
    }

    /** Whoever this agent is holding a stated condition from, so it need not ask again. */
    public Set<String> whoNamedAPrice() {
        return Set.copyOf(namedAPrice);
    }

    /**
     * Every map reachable from here along doors the agent has walked, with how far each is
     * and which door starts the walk.
     *
     * The same breadth-first walk as the routes above, but finished rather than stopped at
     * the first goal, so something can weigh every place against every other instead of
     * asking one question at a time and taking the first yes.
     */
    public Map<String, Hop> reachableFrom(String fromMap) {
        Map<String, Hop> reached = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(fromMap);
        reached.put(fromMap, new Hop(0, null));
        while (!queue.isEmpty()) {
            String here = queue.poll();
            Hop sofar = reached.get(here);
            for (Map.Entry<String, String> exit : exits.getOrDefault(here, Map.of()).entrySet()) {
                String destination = exit.getValue();
                if (reached.containsKey(destination)) {
                    continue;
                }
                reached.put(destination, new Hop(sofar.hops() + 1,
                        here.equals(fromMap) ? exit.getKey() : sofar.firstDoor()));
                queue.add(destination);
            }
        }
        return reached;
    }

    /** How many doors away a map is, and the door to take first. Null door means here. */
    public record Hop(int hops, String firstDoor) {
    }

    /** People known to stand in this map that this agent has not heard speak, leaving some out. */
    public int strangersIn(String mapRef, Set<String> leavingOut) {
        int count = 0;
        for (String person : peopleIn.getOrDefault(mapRef, Set.of())) {
            if (!spokenTo.contains(person) && !leavingOut.contains(person)) {
                count++;
            }
        }
        return count;
    }

    /** Whether any of these people is known to stand in this map. */
    public boolean anyOfIn(String mapRef, Set<String> people) {
        for (String person : peopleIn.getOrDefault(mapRef, Set.of())) {
            if (people.contains(person)) {
                return true;
            }
        }
        return false;
    }

    /** Whether something living has been seen in this map. */
    public boolean huntingIn(String mapRef) {
        return hunting.contains(mapRef);
    }

    /** The shortest walk from here to a particular map, for when the agent has an errand. */
    public Optional<Route> routeTo(String fromMap, String toMap) {
        if (fromMap.equals(toMap)) {
            return Optional.empty();
        }
        return search(fromMap, toMap::equals, "errand");
    }

    /**
     * Breadth-first, so the answer is the fewest doors rather than the first path found.
     *
     * Only the first door of the route is returned. The rest of it will be re-derived on
     * arrival from beliefs that may by then have changed, which is the honest way for
     * something that learns as it walks to follow a plan.
     */
    private Optional<Route> search(String fromMap, Predicate<String> isGoal, String why) {
        if (isGoal.test(fromMap)) {
            return unopenedDoorsIn(fromMap).stream().sorted().findFirst()
                    .map(door -> new Route(door, 0, fromMap, why));
        }

        Map<String, String> firstDoorTo = new HashMap<>();
        Map<String, Integer> hopsTo = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(fromMap);
        hopsTo.put(fromMap, 0);

        while (!queue.isEmpty()) {
            String here = queue.poll();
            for (Map.Entry<String, String> exit : exits.getOrDefault(here, Map.of()).entrySet()) {
                String destination = exit.getValue();
                if (hopsTo.containsKey(destination)) {
                    continue;
                }
                hopsTo.put(destination, hopsTo.get(here) + 1);
                firstDoorTo.put(destination,
                        here.equals(fromMap) ? exit.getKey() : firstDoorTo.get(here));
                if (isGoal.test(destination)) {
                    return Optional.of(new Route(firstDoorTo.get(destination),
                            hopsTo.get(destination), destination, why));
                }
                queue.add(destination);
            }
        }
        return Optional.empty();
    }

    /**
     * A journey the agent can start now.
     *
     * @param firstDoor the door to take from where it is standing
     * @param hops how many maps away the goal is, zero meaning this one
     * @param towards the map the journey is for, which is not where the first door leads
     * @param why what this journey is for, so the trace says which of the three reasons to
     *            travel won rather than leaving it to be inferred from positions
     */
    public record Route(String firstDoor, int hops, String towards, String why) {
    }

    public static String portalRef(String mapRef, String door) {
        return "portal:" + mapRef.substring("map:".length()) + "/" + door;
    }

    public static String portalRef(int mapId, String door) {
        return "portal:" + mapId + "/" + door;
    }

    public static String mapRef(int mapId) {
        return "map:" + mapId;
    }

    private static String mapOf(String portalRef) {
        int slash = portalRef.indexOf('/');
        return slash < 0 || !portalRef.startsWith("portal:")
                ? null
                : "map:" + portalRef.substring("portal:".length(), slash);
    }

    private static String doorOf(String portalRef) {
        int slash = portalRef.indexOf('/');
        return slash < 0 ? null : portalRef.substring(slash + 1);
    }
}
