package agents.world;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.StringUtil;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The portals an agent can see in the map it is standing in.
 *
 * Read from {@code Map.wz}, which every player's client also loads - this is what is drawn
 * on the screen, not privileged server state, so perceiving it is no different from a player
 * looking at where they are.
 *
 * <strong>What is deliberately not read: {@code tm}, the portal's target map.</strong> The
 * file has it, and taking it would hand an agent the entire connectivity of the world for
 * free - which is most of what "explore" means here. A player sees a doorway and has to walk
 * through it to find out where it goes, and so does an agent. Where a portal leads is
 * learned by using it and perceiving the map change, and it becomes an ordinary belief with
 * an episode behind it like anything else.
 */
public class MapGeometry {
    private static final Map<Integer, List<PortalSighting>> CACHE = new HashMap<>();
    private static final Map<Integer, List<Ground>> GROUND = new HashMap<>();
    private static final Map<Integer, List<Climb>> CLIMBS = new HashMap<>();

    /** A portal as it appears on screen: somewhere to stand, and a name to refer to it by. */
    public record PortalSighting(String name, Point position, int type) {

        /** Spawn points are where you arrive, not somewhere you can go. */
        boolean isUsable() {
            return type != SPAWN_POINT && !name.isBlank() && !"sp".equals(name);
        }
    }

    private static final int SPAWN_POINT = 0;

    public static synchronized List<PortalSighting> portalsIn(int mapId) {
        return CACHE.computeIfAbsent(mapId, MapGeometry::load);
    }

    /** The portals worth trying, in no particular order. */
    public static List<PortalSighting> usablePortalsIn(int mapId) {
        return portalsIn(mapId).stream().filter(PortalSighting::isUsable).toList();
    }

    private static List<PortalSighting> load(int mapId) {
        DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Data mapData = provider.getData(pathFor(mapId));
        if (mapData == null) {
            return List.of();
        }

        Data portals = mapData.getChildByPath("portal");
        if (portals == null) {
            return List.of();
        }

        List<PortalSighting> sightings = new ArrayList<>();
        for (Data portal : portals) {
            String name = DataTool.getString(portal.getChildByPath("pn"), "");
            int type = DataTool.getInt(portal.getChildByPath("pt"), 0);
            int x = DataTool.getInt(portal.getChildByPath("x"), 0);
            int y = DataTool.getInt(portal.getChildByPath("y"), 0);
            sightings.add(new PortalSighting(name, new Point(x, y), type));
        }
        return List.copyOf(sightings);
    }

    /**
     * A stretch of walkable floor: the footholds a character stands on.
     *
     * Read from the same file and for the same reason as the portals - it is drawn on every
     * player's screen, so knowing where the floor is under your feet is not privileged
     * knowledge, it is looking down. Without it an agent walking from one place to another
     * cuts a straight line through the level and appears to stroll through the scenery,
     * because nothing was stopping it.
     */
    public record Ground(int x1, int y1, int x2, int y2) {

        boolean spans(int x) {
            return x >= Math.min(x1, x2) && x <= Math.max(x1, x2);
        }

        /** Height of this floor under a given x, interpolated along its slope. */
        int heightAt(int x) {
            if (x1 == x2) {
                return Math.min(y1, y2);
            }
            double along = (double) (x - x1) / (x2 - x1);
            return (int) Math.round(y1 + (y2 - y1) * along);
        }
    }

    /**
     * The floor a character at this position would be standing on.
     *
     * The nearest foothold at or below the given point, because a character walking off the
     * end of a platform falls to what is underneath rather than to whatever is closest in a
     * straight line.
     *
     * @return the ground height, or the position's own y when this map has no floor there -
     *         better to leave the agent where it is than to drop it through the world
     */
    public static synchronized int groundUnder(int mapId, int x, int y) {
        int best = Integer.MAX_VALUE;
        int found = y;
        for (Ground ground : groundIn(mapId)) {
            if (!ground.spans(x)) {
                continue;
            }
            int height = ground.heightAt(x);
            int drop = height - y;
            if (drop >= -Math.abs(STEP_UP) && drop < best) {
                best = drop;
                found = height;
            }
        }
        return found;
    }

    /**
     * How far above its feet a character may still be considered on a floor. Stairs and
     * slopes mean the exact pixel is never quite right.
     */
    private static final int STEP_UP = 30;

    public static synchronized List<Ground> groundIn(int mapId) {
        return GROUND.computeIfAbsent(mapId, MapGeometry::loadGround);
    }

    private static List<Ground> loadGround(int mapId) {
        DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Data mapData = provider.getData(pathFor(mapId));
        if (mapData == null) {
            return List.of();
        }
        Data footholds = mapData.getChildByPath("foothold");
        if (footholds == null) {
            return List.of();
        }

        // Map.wz nests these as foothold/<layer>/<group>/<id>, and only the leaves carry
        // coordinates. The nesting is the client's business, not ours: any floor is floor.
        List<Ground> ground = new ArrayList<>();
        for (Data layer : footholds) {
            for (Data group : layer) {
                for (Data foothold : group) {
                    Data x1 = foothold.getChildByPath("x1");
                    if (x1 == null) {
                        continue;
                    }
                    ground.add(new Ground(
                            DataTool.getInt(x1, 0),
                            DataTool.getInt(foothold.getChildByPath("y1"), 0),
                            DataTool.getInt(foothold.getChildByPath("x2"), 0),
                            DataTool.getInt(foothold.getChildByPath("y2"), 0)));
                }
            }
        }
        return List.copyOf(ground);
    }

    /**
     * A rope or a ladder: the only way up, and the reason an agent needs to know about them.
     *
     * Read from the same file as the floor and the doors, and drawn on every player's
     * screen, so noticing one is looking rather than privilege. Without them an agent is
     * confined to whatever foothold it happens to land on. Shanks, who sells the only
     * passage off Maple Island, stands at y=-105 in Southperry while an agent arrives at
     * y=500 - six hundred pixels above a character that could only ever walk sideways.
     * Every NPC on a platform, every drop on a ledge and every door up a flight of stairs
     * had the same problem, and it looked like a dozen different bugs.
     *
     * @param top the smaller y, because the screen's y grows downwards
     */
    public record Climb(int x, int top, int bottom, boolean ladder) {

        /** The end a character would board it at, coming from this height. */
        public int endNearest(int y) {
            return Math.abs(y - top) <= Math.abs(y - bottom) ? top : bottom;
        }

        /** The end it would carry them to, having boarded it from this height. */
        public int endAwayFrom(int y) {
            return endNearest(y) == top ? bottom : top;
        }

        public int height() {
            return bottom - top;
        }
    }

    /**
     * How far from an end of a rope a character may be and still get on it. Generous,
     * because the floor beside a rope rarely sits at exactly the height the rope stops.
     */
    private static final int REACH = 60;

    /**
     * A rope or ladder that would get a character closer to a height it cannot walk to.
     *
     * Deliberately one rung of the journey rather than a route. A climb qualifies when a
     * character could board it - the floor at its x is level with one of its ends - and when
     * riding it would leave them vertically nearer the target than they are now. Chaining
     * happens by itself: each decision picks the best next climb from wherever the last one
     * left the agent, so a two-rope ascent needs no planner, and an agent that finds itself
     * somewhere unexpected re-decides from there instead of following a stale plan.
     *
     * Nearest by walking distance rather than by height covered, because the rope beside you
     * is worth more than the better rope across the map.
     */
    public static Optional<Climb> climbTowards(int mapId, int x, int fromY, int toY) {
        int gap = Math.abs(fromY - toY);
        return climbsIn(mapId).stream()
                .filter(climb -> canBoard(mapId, climb, fromY))
                .filter(climb -> Math.abs(climb.endAwayFrom(boardingHeight(mapId, climb, fromY)) - toY) < gap)
                .min(Comparator.comparingInt(climb -> Math.abs(climb.x() - x)));
    }

    /** The height a character ends up at by walking to this rope's foot. */
    private static int boardingHeight(int mapId, Climb climb, int fromY) {
        return groundUnder(mapId, climb.x(), fromY);
    }

    /**
     * Whether walking to this rope would put a character at one of its ends.
     *
     * Checked against the floor at the rope's x rather than the character's current height,
     * because walking there changes how high it is standing - which is the whole reason the
     * first attempt at this rejected every rope in Southperry.
     */
    private static boolean canBoard(int mapId, Climb climb, int fromY) {
        int standing = boardingHeight(mapId, climb, fromY);
        return Math.abs(standing - climb.endNearest(standing)) <= REACH;
    }

    public static synchronized List<Climb> climbsIn(int mapId) {
        return CLIMBS.computeIfAbsent(mapId, MapGeometry::loadClimbs);
    }

    private static List<Climb> loadClimbs(int mapId) {
        DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Data mapData = provider.getData(pathFor(mapId));
        if (mapData == null) {
            return List.of();
        }
        Data ropes = mapData.getChildByPath("ladderRope");
        if (ropes == null) {
            return List.of();
        }

        List<Climb> climbs = new ArrayList<>();
        for (Data rope : ropes) {
            Data x = rope.getChildByPath("x");
            if (x == null) {
                continue;
            }
            int y1 = DataTool.getInt(rope.getChildByPath("y1"), 0);
            int y2 = DataTool.getInt(rope.getChildByPath("y2"), 0);
            climbs.add(new Climb(DataTool.getInt(x, 0),
                    Math.min(y1, y2), Math.max(y1, y2),
                    DataTool.getInt(rope.getChildByPath("l"), 0) != 0));
        }
        return List.copyOf(climbs);
    }

    /** @see server.maps.MapFactory#getMapName */
    private static String pathFor(int mapId) {
        String padded = StringUtil.getLeftPaddedStr(Integer.toString(mapId), '0', 9);
        return "Map/Map" + (mapId / 100000000) + "/" + padded + ".img";
    }
}
