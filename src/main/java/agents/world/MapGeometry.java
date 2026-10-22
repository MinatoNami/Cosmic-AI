package agents.world;

import provider.Data;
import provider.DataProvider;
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
 *
 * <p>The one exception is a portal whose target is the map it stands in: a hop across the
 * same screen, which the client makes by itself - the server is not even told - and which a
 * player sees happen in front of them. {@link #hopsIn} reads those, and only those. Without
 * them an agent on Pet-Walking Road could never reach its only exit, which is at the end of a
 * hop from the floor, and walked into the hop, saw the map not change, and wrote it off.
 */
public class MapGeometry {
    private static final Map<Integer, List<PortalSighting>> CACHE = new HashMap<>();
    private static final Map<Integer, List<Ground>> GROUND = new HashMap<>();
    private static final Map<Integer, List<Climb>> CLIMBS = new HashMap<>();

    /**
     * A portal as it appears on screen: somewhere to stand, and a name to refer to it by.
     *
     * {@code id} is its number in the map's portal list, which is how the server names the
     * one a character arrives at - see {@link #arrivalPoint}.
     */
    public record PortalSighting(int id, String name, Point position, int type) {

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
        // A hop within the map is a way to move about, not a way out of it.
        java.util.Set<String> hops = new java.util.HashSet<>();
        hopsIn(mapId).forEach(hop -> hops.add(hop.name()));
        java.util.Set<String> nowhere = leadingNowhereIn(mapId);
        return portalsIn(mapId).stream().filter(PortalSighting::isUsable)
                .filter(portal -> !hops.contains(portal.name()))
                .filter(portal -> !nowhere.contains(portal.name())).toList();
    }

    /** No map to go to and no script to send you anywhere. */
    private static final int NO_MAP = 999999999;

    private static final Map<Integer, java.util.Set<String>> NOWHERE = new HashMap<>();

    /**
     * Portals that lead nowhere: no map and no script, only a place to arrive at.
     *
     * The Sleepywood dungeon's jumpSP is where the statue's warp puts you, 570 pixels up a
     * wall. A Bowman took it for a door, walked to the floor beneath it and stood there for
     * five hours, because walking to a portal means walking to the floor under it.
     */
    private static synchronized java.util.Set<String> leadingNowhereIn(int mapId) {
        return NOWHERE.computeIfAbsent(mapId, id -> {
            Data mapData = WzData.of(WZFiles.MAP).getData(pathFor(id));
            if (mapData == null || mapData.getChildByPath("portal") == null) {
                return java.util.Set.of();
            }
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Data portal : mapData.getChildByPath("portal")) {
                String name = DataTool.getString(portal.getChildByPath("pn"), "");
                int to = DataTool.getInt(portal.getChildByPath("tm"), NO_MAP);
                String script = DataTool.getString(portal.getChildByPath("script"), "");
                if (to == NO_MAP && script.isBlank()) {
                    names.add(name);
                }
            }
            // The same name may also be a real door elsewhere in the map; then it is kept.
            for (Data portal : mapData.getChildByPath("portal")) {
                String name = DataTool.getString(portal.getChildByPath("pn"), "");
                int to = DataTool.getInt(portal.getChildByPath("tm"), NO_MAP);
                if (to != NO_MAP || !DataTool.getString(portal.getChildByPath("script"), "").isBlank()) {
                    names.remove(name);
                }
            }
            return java.util.Set.copyOf(names);
        });
    }

    /**
     * Where a character arriving at this portal is standing.
     *
     * Entering a map, the server names the portal you appear at by its number and puts you
     * exactly on it. The client draws you there, so this is where you are - not wherever you
     * happened to be standing in the map you left.
     */
    public static Optional<Point> arrivalPoint(int mapId, int portalId) {
        return portalsIn(mapId).stream()
                .filter(portal -> portal.id() == portalId)
                .map(PortalSighting::position)
                .findFirst();
    }

    private static List<PortalSighting> load(int mapId) {
        DataProvider provider = WzData.of(WZFiles.MAP);
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
            sightings.add(new PortalSighting(portalId(portal), name, new Point(x, y), type));
        }
        return List.copyOf(sightings);
    }

    /** A hop across the same map: step into the portal at {@code from}, come out at {@code to}. */
    public record Hop(String name, Point from, Point to) {
    }

    private static final Map<Integer, List<Hop>> HOPS = new HashMap<>();

    /** The portals in this map that lead to another point in this same map. */
    public static synchronized List<Hop> hopsIn(int mapId) {
        return HOPS.computeIfAbsent(mapId, MapGeometry::loadHops);
    }

    private static List<Hop> loadHops(int mapId) {
        DataProvider provider = WzData.of(WZFiles.MAP);
        Data mapData = provider.getData(pathFor(mapId));
        if (mapData == null || mapData.getChildByPath("portal") == null) {
            return List.of();
        }
        Map<String, Point> byName = new HashMap<>();
        for (PortalSighting sighting : portalsIn(mapId)) {
            byName.putIfAbsent(sighting.name(), sighting.position());
        }
        List<Hop> hops = new ArrayList<>();
        for (Data portal : mapData.getChildByPath("portal")) {
            if (DataTool.getInt(portal.getChildByPath("tm"), 0) != mapId) {
                continue;       // somewhere else, or nowhere: not ours to read
            }
            Point to = byName.get(DataTool.getString(portal.getChildByPath("tn"), ""));
            if (to != null) {
                hops.add(new Hop(DataTool.getString(portal.getChildByPath("pn"), ""),
                        new Point(DataTool.getInt(portal.getChildByPath("x"), 0),
                                DataTool.getInt(portal.getChildByPath("y"), 0)), to));
            }
        }
        return List.copyOf(hops);
    }

    /**
     * A way up or across a map by way of a side room: step into the portal at {@code from}, and
     * leave the room by {@code roomExit}, which comes back out at {@code to}.
     *
     * The tree north of Ellinia is climbed this way. Its portals lead into small rooms inside
     * the trunk, and each room's doors come back out higher up the same tree; the Magician's
     * instructor stands at the top. Nothing here knew that: a map's own floors and ropes did
     * not reach, and between maps the rooms were only somewhere to go and come back from. A
     * Magician with Grendel's letter stood at the foot of the tree for three hours.
     */
    /**
     * @param map     the map it is a way across
     * @param arrival where the room is entered, on its side of the portal
     * @param exit    where {@code roomExit} stands in the room
     */
    public record RoomHop(int map, String portal, Point from, Point to, int room, Point arrival,
                          String roomExit, Point exit) {
    }

    private static final Map<Integer, List<RoomHop>> ROOM_HOPS = new HashMap<>();

    /** Every way across this map through a side room whose doors all lead back into it. */
    public static synchronized List<RoomHop> roomHopsIn(int mapId) {
        return ROOM_HOPS.computeIfAbsent(mapId, MapGeometry::loadRoomHops);
    }

    private static List<RoomHop> loadRoomHops(int mapId) {
        DataProvider provider = WzData.of(WZFiles.MAP);
        Data mapData = provider.getData(pathFor(mapId));
        if (mapData == null || mapData.getChildByPath("portal") == null) {
            return List.of();
        }
        Map<String, Point> here = new HashMap<>();
        for (PortalSighting sighting : portalsIn(mapId)) {
            here.putIfAbsent(sighting.name(), sighting.position());
        }
        List<RoomHop> hops = new ArrayList<>();
        for (Data portal : mapData.getChildByPath("portal")) {
            int room = DataTool.getInt(portal.getChildByPath("tm"), 0);
            if (room <= 0 || room == mapId || room == 999999999) {
                continue;
            }
            Data roomData = provider.getData(pathFor(room));
            if (roomData == null || roomData.getChildByPath("portal") == null) {
                continue;
            }
            String name = DataTool.getString(portal.getChildByPath("pn"), "");
            Point from = new Point(DataTool.getInt(portal.getChildByPath("x"), 0),
                    DataTool.getInt(portal.getChildByPath("y"), 0));
            Map<String, Point> inRoom = new HashMap<>();
            for (PortalSighting sighting : portalsIn(room)) {
                inRoom.putIfAbsent(sighting.name(), sighting.position());
            }
            Point arrival = inRoom.get(DataTool.getString(portal.getChildByPath("tn"), ""));
            if (arrival == null) {
                continue;
            }
            List<RoomHop> through = new ArrayList<>();
            boolean sideRoom = true;
            for (Data door : roomData.getChildByPath("portal")) {
                String doorName = DataTool.getString(door.getChildByPath("pn"), "");
                int leadsTo = DataTool.getInt(door.getChildByPath("tm"), 999999999);
                if (doorName.isBlank() || "sp".equals(doorName) || leadsTo == 999999999 || leadsTo == room) {
                    continue;
                }
                if (leadsTo != mapId) {
                    sideRoom = false;       // a room with a way on elsewhere is a place, not a side room
                    break;
                }
                String comesOutAt = DataTool.getString(door.getChildByPath("tn"), "");
                Point to = here.get(comesOutAt);
                if (to != null && !comesOutAt.equals(name) && inRoom.containsKey(doorName)) {
                    through.add(new RoomHop(mapId, name, from, to, room, arrival, doorName, inRoom.get(doorName)));
                }
            }
            if (sideRoom) {
                hops.addAll(through);
            }
        }
        return List.copyOf(hops);
    }

    /** The server numbers portals by their node name in the file, and so must we. */
    private static int portalId(Data portal) {
        try {
            return Integer.parseInt(portal.getName());
        } catch (NumberFormatException e) {
            return -1;
        }
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
    public record Ground(int id, int x1, int y1, int x2, int y2) {

        public Ground(int x1, int y1, int x2, int y2) {
            this(0, x1, y1, x2, y2);
        }

        /** A wall: the client treats a vertical foothold as something to stop against, not stand on. */
        public boolean isWall() {
            return x1 == x2;
        }

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
            // Nearest the feet: a floor just overhead has the smaller drop and is not the one
            // being stood on.
            if (drop >= -Math.abs(STEP_UP) && Math.abs(drop) < best) {
                best = Math.abs(drop);
                found = height;
            }
        }
        return found;
    }

    /**
     * The id of the foothold a character at this position would be standing on, or 0.
     *
     * Movement packets carry it, and other clients place a character on the foothold named.
     * Every step used to claim foothold 0, which is how the game says "in the air", so
     * everyone watching saw agents hover and slide rather than walk.
     */
    public static synchronized int footholdUnder(int mapId, int x, int y) {
        int best = Integer.MAX_VALUE;
        int found = 0;
        for (Ground ground : groundIn(mapId)) {
            if (ground.isWall() || !ground.spans(x)) {
                continue;
            }
            int drop = ground.heightAt(x) - y;
            if (drop >= -Math.abs(STEP_UP) && Math.abs(drop) < best) {
                best = Math.abs(drop);
                found = ground.id();
            }
        }
        return found;
    }

    private static int footholdId(Data foothold) {
        try {
            return Integer.parseInt(foothold.getName());
        } catch (NumberFormatException e) {
            return 0;
        }
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
        DataProvider provider = WzData.of(WZFiles.MAP);
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
                            footholdId(foothold),
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
    /**
     * @param offAtTop whether a character can step off at the top onto a floor - the map
     *                 data's "uf"; Swampy Land's rope up from Sleepywood does not let anybody off
     */
    public record Climb(int x, int top, int bottom, boolean ladder, boolean offAtTop) {

        public Climb(int x, int top, int bottom, boolean ladder) {
            this(x, top, bottom, ladder, true);
        }

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
        DataProvider provider = WzData.of(WZFiles.MAP);
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
                    DataTool.getInt(rope.getChildByPath("l"), 0) != 0,
                    DataTool.getInt(rope.getChildByPath("uf"), 1) != 0));
        }
        return List.copyOf(climbs);
    }

    /** @see server.maps.MapFactory#getMapName */
    private static final Map<Integer, Integer> RETURN_TOWNS = new HashMap<>();

    /**
     * The town a return scroll read in this map goes to - its returnMap, the same thing the
     * server reads - or -1 when it has none.
     */
    public static synchronized int returnTownOf(int mapId) {
        return RETURN_TOWNS.computeIfAbsent(mapId, id -> {
            Data mapData = WzData.of(WZFiles.MAP).getData(pathFor(id));
            if (mapData == null) {
                return -1;
            }
            int town = DataTool.getInt(mapData.getChildByPath("info/returnMap"), -1);
            return town <= 0 || town == 999999999 || town == id ? -1 : town;
        });
    }

    private static String pathFor(int mapId) {
        String padded = StringUtil.getLeftPaddedStr(Integer.toString(mapId), '0', 9);
        return "Map/Map" + (mapId / 100000000) + "/" + padded + ".img";
    }
}
