package agents.world;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

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
     * gravity, which peaks near 77 pixels; a little less, so a jump that only just makes it
     * on paper is not relied on.
     */
    static final int JUMP_RISE = 70;

    /** How far across a running jump carries, end of one floor to the start of the next. */
    static final int JUMP_REACH = 110;

    /** Footholds whose ends are this close are one floor. */
    private static final int JOIN = 3;

    /** How far from a rope's end a floor may be and still be where you get on or off. */
    private static final int ROPE_REACH = 60;

    /** How far a character may be above a floor and still be standing on it. */
    private static final int STANDING = 30;

    /** Walking cost of a change of floor, so a route does not zig-zag for a pixel saved. */
    private static final int TRANSITION_COST = 40;

    private static final Map<Integer, Graph> GRAPHS = new HashMap<>();

    private Navigator() {
    }

    public enum Kind { WALK, DROP, JUMP, CLIMB }

    /**
     * The next thing to do: walk to {@code departX} on the current floor, then make the move.
     * For {@link Kind#WALK} the walk is the whole of it and {@code landing} is the target.
     */
    public record Step(Kind kind, int departX, Point landing, MapGeometry.Climb rope) {
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
        return graph.firstEdge(start, from.x, goal, to.x)
                .map(edge -> new Step(edge.kind, edge.departX,
                        new Point(edge.arriveX, graph.platforms.get(edge.to).heightAt(edge.arriveX)),
                        edge.rope));
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
        return GRAPHS.computeIfAbsent(mapId,
                id -> Graph.build(MapGeometry.groundIn(id), MapGeometry.climbsIn(id)));
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

    record Edge(int to, Kind kind, int departX, int arriveX, MapGeometry.Climb rope) {
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
                int drop = p.heightAt(x) - y;
                if (drop >= -STANDING && drop < bestDrop) {
                    bestDrop = drop;
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
                        if (gap < 0 || gap > JUMP_REACH) {
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
                    for (int x : new int[]{lo + 4, (lo + hi) / 2, hi - 4}) {
                        if (x < lo || x > hi) {
                            continue;
                        }
                        int rise = p.heightAt(x) - q.heightAt(x);
                        if (rise > STANDING && rise <= JUMP_RISE) {
                            edges.get(from).add(new Edge(to, Kind.JUMP, x, x, null));
                            break;
                        }
                    }
                }
            }

            // Ropes and ladders join the floor at their foot to the floor at their head.
            for (MapGeometry.Climb rope : climbs) {
                int bottom = floorNear(rope.x(), rope.bottom());
                int top = floorNear(rope.x(), rope.top());
                if (bottom >= 0 && top >= 0 && bottom != top) {
                    edges.get(bottom).add(new Edge(top, Kind.CLIMB, rope.x(), rope.x(), rope));
                    edges.get(top).add(new Edge(bottom, Kind.CLIMB, rope.x(), rope.x(), rope));
                }
            }
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
                    long next = cost[here] + Math.abs(atX[here] - edge.departX) + TRANSITION_COST
                            + (edge.kind == Kind.CLIMB && edge.rope != null ? edge.rope.height() : 0);
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
