package agents.world;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.StringUtil;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** @see server.maps.MapFactory#getMapName */
    private static String pathFor(int mapId) {
        String padded = StringUtil.getLeftPaddedStr(Integer.toString(mapId), '0', 9);
        return "Map/Map" + (mapId / 100000000) + "/" + padded + ".img";
    }
}
