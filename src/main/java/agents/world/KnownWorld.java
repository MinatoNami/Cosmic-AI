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
        return search(fromMap, map -> !unopenedDoorsIn(map).isEmpty());
    }

    /** The shortest walk from here to a particular map, for when the agent has an errand. */
    public Optional<Route> routeTo(String fromMap, String toMap) {
        if (fromMap.equals(toMap)) {
            return Optional.empty();
        }
        return search(fromMap, toMap::equals);
    }

    /**
     * Breadth-first, so the answer is the fewest doors rather than the first path found.
     *
     * Only the first door of the route is returned. The rest of it will be re-derived on
     * arrival from beliefs that may by then have changed, which is the honest way for
     * something that learns as it walks to follow a plan.
     */
    private Optional<Route> search(String fromMap, Predicate<String> isGoal) {
        if (isGoal.test(fromMap)) {
            return unopenedDoorsIn(fromMap).stream().sorted().findFirst()
                    .map(door -> new Route(door, 0, fromMap));
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
                            hopsTo.get(destination), destination));
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
     */
    public record Route(String firstDoor, int hops, String towards) {
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
