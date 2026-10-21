package agents.world;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * How to get from one place in a map to another the way a character could: along floors,
 * off edges, up jumps and ropes.
 *
 * <p>Before this, walking was horizontal and height was an afterthought. A target on another
 * platform got a rope if one happened to start where the agent stood; otherwise the last step
 * simply landed on the target, height and all, so onlookers watched agents pop 120 pixels
 * straight up onto ledges and back down. The alternative - refusing - leaves them pacing under
 * the ledge for ever. Knowing how platforms join is what makes both unnecessary.
 *
 * <p>Floors are the footholds in Map.wz, which every player's client draws: knowing where the
 * ground is, is looking down. Footholds whose ends meet form one platform. Platforms are
 * joined by four kinds of move, and a shortest-path search over them gives the first move
 * to make. Only the first: the rest is re-planned from wherever that one ends, which is the
 * same rule the map-to-map routes follow.
 */
public final class Navigator {

    /**
     * How high a jump reaches. A beginner jumps at about 555 px/s against 2000 px/s² of
     * gravity, which peaks near 77 pixels. It was 70, which left the only way up in Right
     * Around Lith Harbor - a 71-pixel jump from one slope to another - out of every route, and
     * an agent heading for Lith Harbor gave up and took the door the other way, back and
     * forth, for as long as anyone watched.
     */
    static final int JUMP_RISE = 76;

    /** The smallest rise onto an overlapping floor that counts as a floor overhead at all. */
    private static final int MIN_STEP_UP = 4;

    /** Floors overlapping by less than this are jumped across, end to start. */
    private static final int BARELY_OVERLAPS = 8;

    /** How finely an overlap between two floors is searched for the easiest place to jump. */
    private static final int JUMP_SEARCH_STEP = 16;

    /** How far across a running jump carries, end of one floor to the start of the next. */
    static final int JUMP_REACH = 110;

    /** Footholds whose ends are this close are one floor. */
    private static final int JOIN = 3;

    /** How far from a rope's end a floor may be and still be where you get on or off. */
    private static final int ROPE_REACH = 60;

    /** How far above the floor a rope's foot can hang and still be caught: a jump and an arm. */
    private static final int ROPE_GRAB = JUMP_RISE + 50;

    /** How far a character may be above a floor and still be standing on it. */
    private static final int STANDING = 30;

    /** Walking cost of a change of floor, so a route does not zig-zag for a pixel saved. */
    private static final int TRANSITION_COST = 40;

    private static final Map<Integer, Graph> GRAPHS = new HashMap<>();

    /**
     * Ropes not to be climbed for now, by map and rope x, until a time. Something sat at the
     * top of a rope in the Field South of Ellinia and knocked a Bowman back to the floor at the
     * same height every climb, for nine hours; there were other ways up.
     */
    private static final Map<Integer, Map<Integer, Long>> BLOCKED = new HashMap<>();

    public static synchronized void blockRope(int mapId, int ropeX, long forMillis) {
        BLOCKED.computeIfAbsent(mapId, m -> new HashMap<>()).put(ropeX, System.currentTimeMillis() + forMillis);
    }

    static synchronized Set<Integer> blockedRopes(int mapId) {
        Map<Integer, Long> ropes = BLOCKED.get(mapId);
        if (ropes == null) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        ropes.values().removeIf(until -> until < now);
        return Set.copyOf(ropes.keySet());
    }

    private static boolean usesARope(Edge edge, Set<Integer> ropeXs) {
        if (edge.rope() != null && ropeXs.contains(edge.rope().x())) {
            return true;
        }
        if (edge.legs() != null) {
            for (Leg leg : edge.legs()) {
                if (ropeXs.contains(leg.rope().x())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Navigator() {
    }

    public enum Kind { WALK, DROP, JUMP, CLIMB, HOP, ROOM }

    /**
     * The next thing to do: walk to {@code departX} on the current floor, then make the move.
     * For {@link Kind#WALK} the walk is the whole of it and {@code landing} is the target.
     */
    /**
     * @param legs for a climb, the ropes in order and how each is left; null for a plain climb
     *             from one end of a single rope to the other, as it always was
     */
    /**
     * @param through for {@link Kind#ROOM}, the side room to go through and the door to leave it
     *                by; null otherwise
     */
    public record Step(Kind kind, int departX, Point landing, MapGeometry.Climb rope, List<Leg> legs,
                       MapGeometry.RoomHop through) {

        public Step(Kind kind, int departX, Point landing, MapGeometry.Climb rope, List<Leg> legs) {
            this(kind, departX, landing, rope, legs, null);
        }

        public Step(Kind kind, int departX, Point landing, MapGeometry.Climb rope) {
            this(kind, departX, landing, rope, null, null);
        }
    }

    /** How a rope is left: stepping off at the top, letting go, or jumping - onto a floor or the next rope. */
    public enum LegEnd { TOP, DROP, JUMP, ROPE }

    /**
     * One rope of a climb: climb it to {@code leaveY}, then leave it the way {@code end} says, for
     * {@code next} - the floor landed on, or the place on the next rope caught.
     */
    public record Leg(MapGeometry.Climb rope, int leaveY, LegEnd end, Point next) {
    }

    /**
     * The first move towards a point, or empty when there is no floor under one end or no
     * known way between them.
     */
    public static Optional<Step> nextStep(int mapId, Point from, Point to) {
        Graph graph = graphOf(mapId);
        int start = graph.platformUnder(from.x, from.y);
        int goal = graph.platformUnder(to.x, to.y);
        if (start < 0 || goal < 0) {
            return Optional.empty();
        }
        if (start == goal) {
            Platform p = graph.platforms.get(goal);
            int x = p.clamp(to.x);
            return Optional.of(new Step(Kind.WALK, x, new Point(x, p.heightAt(x)), null));
        }
        return graph.firstEdge(start, from.x, goal, to.x, blockedRopes(mapId), blockedRooms(mapId))
                .map(edge -> new Step(edge.kind, edge.departX,
                        new Point(edge.arriveX, graph.platforms.get(edge.to).heightAt(edge.arriveX)),
                        edge.rope, edge.legs, edge.through));
    }

    /** The height of the floor a point is standing on, if it is standing on one. */
    public static Optional<Integer> floorHeight(int mapId, Point at) {
        Graph graph = graphOf(mapId);
        int p = graph.platformUnder(at.x, at.y);
        return p < 0 ? Optional.empty() : Optional.of(graph.platforms.get(p).heightAt(at.x));
    }

    /**
     * Where a character that is not standing on anything should come down: the floor below
     * it, or failing that the floor nearest it at all. Agents used to walk off the ends of
     * platforms and carry on at the same height through thin air, because a step with no
     * floor under it kept the height it started at.
     */
    public static Optional<Point> settleFrom(int mapId, Point at) {
        Graph graph = graphOf(mapId);
        int below = graph.platformUnder(at.x, at.y);
        if (below >= 0) {
            return Optional.of(new Point(at.x, graph.platforms.get(below).heightAt(at.x)));
        }
        Point best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Platform p : graph.platforms) {
            int x = p.clamp(at.x);
            Point candidate = new Point(x, p.heightAt(x));
            double distance = candidate.distance(at);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The height of the floor at this x nearest the given height, for stepping off a rope. */
    public static Optional<Integer> floorNear(int mapId, int x, int y) {
        Graph graph = graphOf(mapId);
        int p = graph.floorNear(x, y);
        return p < 0 ? Optional.empty() : Optional.of(graph.platforms.get(p).heightAt(x));
    }

    /**
     * The height of the floor a point is standing on, somewhere else along that same floor.
     *
     * A target along a slope has to be at the slope's height there, not the height the agent
     * is standing at now: uphill, a point at the agent's own height is under the floor, and
     * the route to it went looking for some lower floor instead.
     */
    public static Optional<Integer> heightAlongFloor(int mapId, Point at, int x) {
        Graph graph = graphOf(mapId);
        int p = graph.platformUnder(at.x, at.y);
        if (p < 0) {
            return Optional.empty();
        }
        Platform platform = graph.platforms.get(p);
        return Optional.of(platform.heightAt(platform.clamp(x)));
    }

    /** The far end of the floor a point is standing on, in one direction. */
    public static Optional<Integer> floorEnd(int mapId, Point at, int direction) {
        Graph graph = graphOf(mapId);
        int p = graph.platformUnder(at.x, at.y);
        if (p < 0) {
            return Optional.empty();
        }
        Platform platform = graph.platforms.get(p);
        return Optional.of(direction < 0 ? platform.minX : platform.maxX);
    }

    static synchronized Graph graphOf(int mapId) {
        // Not computeIfAbsent: whether a side room can be crossed is asked of the room's own
        // graph while this one is being built, and a map may not be added to while it is
        // being computed into.
        Graph cached = GRAPHS.get(mapId);
        if (cached != null) {
            return cached;
        }
        Graph graph = Graph.build(MapGeometry.groundIn(mapId), MapGeometry.climbsIn(mapId));
        graph.linkHops(MapGeometry.hopsIn(mapId));
        if (!BUILDING.add(mapId)) {
            return graph;       // asked again while being built: its floors are enough to cross it by
        }
        try {
            graph.linkRoomHops(MapGeometry.roomHopsIn(mapId).stream().filter(Navigator::crossable).toList());
        } finally {
            BUILDING.remove(mapId);
        }
        GRAPHS.put(mapId, graph);
        return graph;
    }

    private static final Set<Integer> BUILDING = new java.util.HashSet<>();

    /**
     * Whether a side room can be walked across, from where it is entered to the door it is left
     * by. Naora Hospital's upper door, out01, comes out on Kerning City's upper floor, and no
     * floor in the hospital reaches it from the door below; taken for a way up Kerning City,
     * Agent0 went in, could not get to it, came out, and went in again - for seventeen hours.
     */
    static boolean crossable(MapGeometry.RoomHop hop) {
        return reaches(graphOf(hop.room()), hop.room(), hop.arrival(), hop.exit());
    }

    /** For tests outside this package. */
    public static synchronized boolean crossableForTest(MapGeometry.RoomHop hop) {
        return crossable(hop);
    }

    /** Whether following the way from one point to another, step after step, gets there. */
    private static boolean reaches(Graph graph, int mapId, Point from, Point to) {
        Point at = settleFrom(mapId, from).orElse(from);
        int goal = graph.platformUnder(to.x, to.y);
        if (goal < 0) {
            Optional<Point> under = settleFrom(mapId, to);
            if (under.isEmpty()) {
                return false;
            }
            goal = graph.platformUnder(under.get().x, under.get().y);
        }
        for (int i = 0; i < CROSSING_STEPS; i++) {
            int here = graph.platformUnder(at.x, at.y);
            if (here < 0) {
                return false;
            }
            if (here == goal) {
                return true;
            }
            Optional<Edge> edge = graph.firstEdge(here, at.x, goal, to.x);
            if (edge.isEmpty()) {
                return false;
            }
            at = new Point(edge.get().arriveX, graph.platforms.get(edge.get().to).heightAt(edge.get().arriveX));
        }
        return false;
    }

    private static final int CROSSING_STEPS = 120;

    /** Side rooms not to be gone through for now, by map, as "portal>exit", until a time. */
    private static final Map<Integer, Map<String, Long>> BLOCKED_ROOMS = new HashMap<>();

    /**
     * Sets a side room aside after a crossing of it failed, so the way across is planned
     * without it for a while rather than straight back through it.
     */
    public static synchronized void blockRoom(MapGeometry.RoomHop hop, long forMillis) {
        BLOCKED_ROOMS.computeIfAbsent(hop.map(), m -> new HashMap<>())
                .put(hop.portal() + ">" + hop.roomExit(), System.currentTimeMillis() + forMillis);
    }

    static synchronized Set<String> blockedRooms(int mapId) {
        Map<String, Long> rooms = BLOCKED_ROOMS.get(mapId);
        if (rooms == null) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        rooms.values().removeIf(until -> until < now);
        return Set.copyOf(rooms.keySet());
    }

    /** One stretch of floor you can walk the length of. */
    record Platform(List<MapGeometry.Ground> segments, int minX, int maxX) {

        boolean spans(int x) {
            return x >= minX && x <= maxX;
        }

        int clamp(int x) {
            return Math.max(minX, Math.min(maxX, x));
        }

        /** Height under x, taken from whichever segment covers it, or the nearest end. */
        int heightAt(int x) {
            MapGeometry.Ground nearest = null;
            int nearestGap = Integer.MAX_VALUE;
            for (MapGeometry.Ground g : segments) {
                if (g.spans(x)) {
                    return g.heightAt(x);
                }
                int gap = Math.min(Math.abs(x - g.x1()), Math.abs(x - g.x2()));
                if (gap < nearestGap) {
                    nearestGap = gap;
                    nearest = g;
                }
            }
            return nearest == null ? 0 : nearest.heightAt(Math.max(Math.min(nearest.x1(), nearest.x2()),
                    Math.min(Math.max(nearest.x1(), nearest.x2()), x)));
        }
    }

    record Edge(int to, Kind kind, int departX, int arriveX, MapGeometry.Climb rope, List<Leg> legs, int climbing,
                MapGeometry.RoomHop through) {

        Edge(int to, Kind kind, int departX, int arriveX, MapGeometry.Climb rope, List<Leg> legs, int climbing) {
            this(to, kind, departX, arriveX, rope, legs, climbing, null);
        }

        Edge(int to, Kind kind, int departX, int arriveX, MapGeometry.Climb rope) {
            this(to, kind, departX, arriveX, rope, null, rope == null ? 0 : rope.height());
        }
    }

    static final class Graph {
        final List<Platform> platforms;
        final List<List<Edge>> edges;

        private Graph(List<Platform> platforms) {
            this.platforms = platforms;
            this.edges = new ArrayList<>();
            platforms.forEach(p -> edges.add(new ArrayList<>()));
        }

        static Graph build(List<MapGeometry.Ground> ground, List<MapGeometry.Climb> climbs) {
            List<MapGeometry.Ground> floors = ground.stream().filter(g -> !g.isWall()).toList();
            Graph graph = new Graph(joinIntoPlatforms(floors));
            graph.link(climbs);
            return graph;
        }

        /** Same-map portals: step in at one floor, come out on another. */
        void linkHops(List<MapGeometry.Hop> hops) {
            for (MapGeometry.Hop hop : hops) {
                int from = platformUnder(hop.from().x, hop.from().y);
                int to = platformUnder(hop.to().x, hop.to().y);
                if (from >= 0 && to >= 0 && from != to) {
                    edges.get(from).add(new Edge(to, Kind.HOP, hop.from().x, hop.to().x, null));
                }
            }
        }

        /**
         * Side rooms: step in at one floor, climb the room, come out on another. Costed as the
         * height gained, as a climb would be, so a rope that does the same is no worse.
         */
        void linkRoomHops(List<MapGeometry.RoomHop> hops) {
            for (MapGeometry.RoomHop hop : hops) {
                int from = platformUnder(hop.from().x, hop.from().y);
                int to = platformUnder(hop.to().x, hop.to().y);
                if (from >= 0 && to >= 0 && from != to) {
                    edges.get(from).add(new Edge(to, Kind.ROOM, hop.from().x, hop.to().x, null, null,
                            Math.abs(hop.from().y - hop.to().y), hop));
                }
            }
        }

        /** Union the footholds whose ends meet. */
        private static List<Platform> joinIntoPlatforms(List<MapGeometry.Ground> floors) {
            int[] parent = new int[floors.size()];
            Arrays.setAll(parent, i -> i);
            for (int i = 0; i < floors.size(); i++) {
                for (int j = i + 1; j < floors.size(); j++) {
                    if (touch(floors.get(i), floors.get(j))) {
                        parent[find(parent, i)] = find(parent, j);
                    }
                }
            }
            Map<Integer, List<MapGeometry.Ground>> groups = new HashMap<>();
            for (int i = 0; i < floors.size(); i++) {
                groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(floors.get(i));
            }
            List<Platform> platforms = new ArrayList<>();
            for (List<MapGeometry.Ground> segments : groups.values()) {
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (MapGeometry.Ground g : segments) {
                    min = Math.min(min, Math.min(g.x1(), g.x2()));
                    max = Math.max(max, Math.max(g.x1(), g.x2()));
                }
                platforms.add(new Platform(List.copyOf(segments), min, max));
            }
            return platforms;
        }

        private static boolean touch(MapGeometry.Ground a, MapGeometry.Ground b) {
            return near(a.x1(), a.y1(), b.x1(), b.y1()) || near(a.x1(), a.y1(), b.x2(), b.y2())
                    || near(a.x2(), a.y2(), b.x1(), b.y1()) || near(a.x2(), a.y2(), b.x2(), b.y2());
        }

        private static boolean near(int x1, int y1, int x2, int y2) {
            return Math.abs(x1 - x2) <= JOIN && Math.abs(y1 - y2) <= JOIN;
        }

        private static int find(int[] parent, int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        /** The floor at or just below a point: the same rule as {@link MapGeometry#groundUnder}. */
        int platformUnder(int x, int y) {
            int best = -1;
            int bestDrop = Integer.MAX_VALUE;
            for (int i = 0; i < platforms.size(); i++) {
                Platform p = platforms.get(i);
                if (!p.spans(x)) {
                    continue;
                }
                // Closest to the feet, not lowest drop: a floor thirty pixels overhead has the
                // lower drop, and picking it put agents standing on a shelf onto the floor above.
                int drop = p.heightAt(x) - y;
                if (drop >= -STANDING && Math.abs(drop) < bestDrop) {
                    bestDrop = Math.abs(drop);
                    best = i;
                }
            }
            return best;
        }

        /** The highest floor under a point, below a given height: where a fall lands. */
        private int landingBelow(int x, int y) {
            return platformUnder(x, y + STANDING + 1);
        }

        private void link(List<MapGeometry.Climb> climbs) {
            for (int from = 0; from < platforms.size(); from++) {
                Platform p = platforms.get(from);
                for (int direction : new int[]{-1, 1}) {
                    int edge = direction < 0 ? p.minX : p.maxX;
                    int hEdge = p.heightAt(edge);

                    // Walk off the end and fall to whatever is below.
                    int offX = edge + direction * 6;
                    int below = landingBelow(offX, hEdge);
                    if (below >= 0 && below != from) {
                        edges.get(from).add(new Edge(below, Kind.DROP, edge, offX, null));
                    }

                    // Jump across to a floor starting within reach, as long as it is not too high.
                    for (int to = 0; to < platforms.size(); to++) {
                        if (to == from) {
                            continue;
                        }
                        Platform q = platforms.get(to);
                        int gap = direction > 0 ? q.minX - edge : edge - q.maxX;
                        // A floor that starts a pixel or two before this one ends is still a jump
                        // across: too little overlap for the jump straight up below to sample, and
                        // the stairs inside the trunk of the tree north of Ellinia climb that way.
                        if (gap < -BARELY_OVERLAPS || gap > JUMP_REACH) {
                            continue;
                        }
                        int arrive = direction > 0 ? q.minX + 4 : q.maxX - 4;
                        arrive = q.clamp(arrive);
                        int rise = hEdge - q.heightAt(arrive);
                        if (rise <= JUMP_RISE) {
                            edges.get(from).add(new Edge(to, Kind.JUMP, edge, arrive, null));
                        }
                    }
                }

                // Jump straight up to a floor overhead.
                for (int to = 0; to < platforms.size(); to++) {
                    if (to == from) {
                        continue;
                    }
                    Platform q = platforms.get(to);
                    int lo = Math.max(p.minX, q.minX);
                    int hi = Math.min(p.maxX, q.maxX);
                    if (lo > hi) {
                        continue;
                    }
                    // The easiest place to jump from: where the floor overhead is least far up.
                    // Floors are often slopes, so the rise changes along the overlap, and
                    // sampling only its ends and middle missed the one place a jump would do.
                    int bestX = Integer.MIN_VALUE;
                    int bestRise = Integer.MAX_VALUE;
                    for (int x = lo + 4; x <= hi - 4; x = x + JUMP_SEARCH_STEP > hi - 4 && x != hi - 4 ? hi - 4 : x + JUMP_SEARCH_STEP) {
                        // Anything more than a few pixels up is a jump. It was "more than the
                        // standing tolerance", which left out the thirty-pixel step between a
                        // ladder's head and the floor with the door to Henesys on it.
                        int rise = p.heightAt(x) - q.heightAt(x);
                        if (rise > MIN_STEP_UP && rise < bestRise) {
                            bestRise = rise;
                            bestX = x;
                        }
                    }
                    if (bestRise <= JUMP_RISE) {
                        edges.get(from).add(new Edge(to, Kind.JUMP, bestX, bestX, null));
                    }
                }
            }

            // Ropes, climbed the way a player climbs them: caught from the floor below or
            // boarded from the top, then left by stepping off at the top, letting go, jumping
            // off sideways onto a floor, or jumping across to the next rope. Many ropes do not
            // let anybody off at the top - Kerning's and Henesys's hillsides climb by a chain
            // of them, rope to rope - and with only end-to-end climbs those were dead ends.
            for (MapGeometry.Climb rope : climbs) {
                int bottom = floorUnderRope(rope.x(), rope.bottom());
                if (bottom >= 0) {
                    climbFrom(bottom, rope, rope.bottom(), climbs);
                }
                int top = rope.offAtTop() ? floorNear(rope.x(), rope.top()) : -1;
                if (top >= 0 && top != bottom) {
                    climbFrom(top, rope, rope.top(), climbs);
                }
            }
        }

        /** Ropes in a chain worth following before giving up on it. */
        private static final int MOST_ROPES = 6;

        /** What a jump costs, against climbing, so a route does not hop for a pixel saved. */
        private static final int JUMP_COST = 60;

        private record Holding(MapGeometry.Climb rope, int y, List<Leg> legs, int cost) {
        }

        /**
         * Every floor reachable from {@code from} by boarding {@code first} at {@code boardY}:
         * climbing, leaving each rope every way it can be left, rope to rope. The cheapest way to
         * each floor becomes one climbing edge, with its legs.
         */
        private void climbFrom(int from, MapGeometry.Climb first, int boardY, List<MapGeometry.Climb> climbs) {
            Map<Integer, Edge> best = new HashMap<>();
            Deque<Holding> queue = new ArrayDeque<>();
            queue.add(new Holding(first, boardY, List.of(), 0));
            Set<MapGeometry.Climb> held = new HashSet<>();
            while (!queue.isEmpty()) {
                Holding h = queue.poll();
                if (!held.add(h.rope()) || h.legs().size() >= MOST_ROPES) {
                    continue;
                }
                MapGeometry.Climb rope = h.rope();
                if (rope.offAtTop()) {
                    int top = floorNear(rope.x(), rope.top());
                    if (top >= 0) {
                        keep(best, from, first, h, new Leg(rope, rope.top(), LegEnd.TOP,
                                new Point(rope.x(), platforms.get(top).heightAt(rope.x()))), top,
                                Math.abs(h.y() - rope.top()));
                    }
                }
                int under = platformUnder(rope.x(), rope.bottom());
                if (under >= 0) {
                    keep(best, from, first, h, new Leg(rope, rope.bottom(), LegEnd.DROP,
                            new Point(rope.x(), platforms.get(under).heightAt(rope.x()))), under,
                            Math.abs(h.y() - rope.bottom()));
                }
                for (int i = 0; i < platforms.size(); i++) {
                    Platform p = platforms.get(i);
                    for (int side : new int[] {-1, 1}) {
                        int x = side < 0 ? Math.min(p.maxX(), rope.x() - 1) : Math.max(p.minX(), rope.x() + 1);
                        if (!p.spans(x) || Math.abs(x - rope.x()) > JUMP_REACH) {
                            continue;
                        }
                        int floorY = p.heightAt(x);
                        if (floorY < rope.top() - JUMP_RISE) {
                            continue;       // above where a jump from the top could reach
                        }
                        int leaveY = Math.max(rope.top(), Math.min(rope.bottom(), floorY));
                        keep(best, from, first, h, new Leg(rope, leaveY, LegEnd.JUMP, new Point(x, floorY)),
                                i, Math.abs(h.y() - leaveY) + JUMP_COST);
                    }
                }
                for (MapGeometry.Climb next : climbs) {
                    if (next == rope || held.contains(next) || Math.abs(next.x() - rope.x()) > JUMP_REACH) {
                        continue;
                    }
                    int leaveY = Math.max(rope.top(), Math.min(rope.bottom(), next.top()));
                    int catchY = Math.max(next.top(), Math.min(next.bottom(), leaveY - JUMP_RISE / 2));
                    if (catchY < leaveY - JUMP_RISE || catchY > next.bottom()) {
                        continue;
                    }
                    List<Leg> legs = new ArrayList<>(h.legs());
                    legs.add(new Leg(rope, leaveY, LegEnd.ROPE, new Point(next.x(), catchY)));
                    queue.add(new Holding(next, catchY, legs, h.cost() + Math.abs(h.y() - leaveY) + JUMP_COST));
                }
            }
            best.values().forEach(edge -> edges.get(from).add(edge));
        }

        /** The cheapest climbing edge to each floor, legs and all. */
        private static void keep(Map<Integer, Edge> best, int from, MapGeometry.Climb first, Holding h, Leg last,
                                 int to, int climbed) {
            if (to == from) {
                return;
            }
            int cost = h.cost() + climbed;
            Edge current = best.get(to);
            if (current != null && current.climbing() <= cost) {
                return;
            }
            List<Leg> legs = new ArrayList<>(h.legs());
            legs.add(last);
            best.put(to, new Edge(to, Kind.CLIMB, first.x(), last.next().x, first, List.copyOf(legs), cost));
        }

        /**
         * The floor a rope can be caught from: one a little above its foot, or below it by as
         * much as a jump and an arm. L Forest II's rope out of the ground floor by its west door
         * hangs 71 pixels up - caught with a jump, and with it left out the ground floor was a
         * dead end, and so was the whole map from that door.
         */
        private int floorUnderRope(int x, int y) {
            int best = -1;
            int bestGap = Integer.MAX_VALUE;
            for (int i = 0; i < platforms.size(); i++) {
                Platform p = platforms.get(i);
                if (!p.spans(x)) {
                    continue;
                }
                int below = p.heightAt(x) - y;      // positive: the floor is under the rope's foot
                if (below < -ROPE_REACH || below > ROPE_GRAB) {
                    continue;
                }
                int gap = Math.abs(below);
                if (gap < bestGap) {
                    bestGap = gap;
                    best = i;
                }
            }
            return best;
        }

        /** The floor at this x whose height is nearest a rope's end, within reach of it. */
        private int floorNear(int x, int y) {
            int best = -1;
            int bestGap = ROPE_REACH + 1;
            for (int i = 0; i < platforms.size(); i++) {
                Platform p = platforms.get(i);
                if (!p.spans(x)) {
                    continue;
                }
                int gap = Math.abs(p.heightAt(x) - y);
                if (gap < bestGap) {
                    bestGap = gap;
                    best = i;
                }
            }
            return best;
        }

        /**
         * Cheapest route by distance walked plus a cost per change of floor, and the first
         * move on it. Distance is measured from where the agent will actually be on each
         * floor - where the last move left it - rather than from the floor as a whole.
         */
        Optional<Edge> firstEdge(int start, int startX, int goal, int goalX) {
            return firstEdge(start, startX, goal, goalX, Set.of());
        }

        Optional<Edge> firstEdge(int start, int startX, int goal, int goalX, Set<Integer> blockedRopeXs) {
            return firstEdge(start, startX, goal, goalX, blockedRopeXs, Set.of());
        }

        Optional<Edge> firstEdge(int start, int startX, int goal, int goalX, Set<Integer> blockedRopeXs,
                                 Set<String> blockedRooms) {
            int n = platforms.size();
            long[] cost = new long[n];
            int[] atX = new int[n];
            Edge[] first = new Edge[n];
            Arrays.fill(cost, Long.MAX_VALUE);
            cost[start] = 0;
            atX[start] = startX;
            PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
            queue.add(new long[]{0, start});
            while (!queue.isEmpty()) {
                long[] head = queue.poll();
                int here = (int) head[1];
                if (head[0] > cost[here]) {
                    continue;
                }
                if (here == goal) {
                    break;
                }
                for (Edge edge : edges.get(here)) {
                    if (!blockedRopeXs.isEmpty() && usesARope(edge, blockedRopeXs)) {
                        continue;
                    }
                    if (edge.through != null && blockedRooms.contains(edge.through.portal() + ">" + edge.through.roomExit())) {
                        continue;
                    }
                    long next = cost[here] + Math.abs(atX[here] - edge.departX) + TRANSITION_COST
                            + (edge.kind == Kind.CLIMB || edge.kind == Kind.ROOM ? edge.climbing : 0);
                    if (next < cost[edge.to]) {
                        cost[edge.to] = next;
                        atX[edge.to] = edge.arriveX;
                        first[edge.to] = here == start ? edge : first[here];
                        queue.add(new long[]{next, edge.to});
                    }
                }
            }
            return Optional.ofNullable(first[goal]);
        }
    }
}
