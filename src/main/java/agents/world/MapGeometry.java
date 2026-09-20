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

    /** @see server.maps.MapFactory#getMapName */
    private static String pathFor(int mapId) {
        String padded = StringUtil.getLeftPaddedStr(Integer.toString(mapId), '0', 9);
        return "Map/Map" + (mapId / 100000000) + "/" + padded + ".img";
    }
}
