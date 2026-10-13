package agents.world;

import provider.Data;
import provider.DataProvider;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.StringUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which door goes where, as the world map shows it.
 *
 * <p>A player opens the world map and sees how the towns and fields join up. The agents never
 * had that: every link between maps was learned by walking through it, or inherited from
 * somebody who had - and a link learned wrong stayed wrong. Happyville's st00 "led to
 * Sleepywood" because an NPC's warp was blamed on it; Split Road's way to Southperry was
 * inherited as going three places at once and believed to go nowhere; Sleepywood and Amoria,
 * reached by an NPC, had no known way out on foot at all. A week of fixes went into undoing
 * mislearned links one at a time.
 *
 * <p>This is the map's own answer for ordinary portals - those the game data says lead to a
 * given map. A portal run by a script names no destination in the data, so those are still
 * learned by trying them, as are monsters, people, quests and danger.
 */
public final class Atlas {

    /** The data's way of saying "a script decides". */
    private static final int SCRIPTED = 999999999;

    private final Map<Integer, Map<String, String>> byMap = new HashMap<>();
    private final Map<Integer, Map<String, String>> arrivals = new HashMap<>();

    /** Door name to the map it leads to, for one map; empty for a map the data has nothing on. */
    public synchronized Map<String, String> exitsOf(int mapId) {
        return byMap.computeIfAbsent(mapId, id -> load(id, "tm"));
    }

    /** The portal a door brings a character out at, on the far side; null if the data does not say. */
    public synchronized String arrivalOf(int mapId, String door) {
        return arrivals.computeIfAbsent(mapId, id -> load(id, "tn")).get(door);
    }

    private static Map<String, String> load(int mapId, String what) {
        DataProvider provider = WzData.of(WZFiles.MAP);
        String padded = StringUtil.getLeftPaddedStr(Integer.toString(mapId), '0', 9);
        Data map = provider.getData("Map/Map" + (mapId / 100000000) + "/" + padded + ".img");
        if (map == null || map.getChildByPath("portal") == null) {
            return Map.of();
        }
        Map<String, String> exits = new TreeMap<>();
        for (Data portal : map.getChildByPath("portal")) {
            String name = DataTool.getString(portal.getChildByPath("pn"), "");
            int to = DataTool.getInt(portal.getChildByPath("tm"), SCRIPTED);
            if (name.isBlank() || "sp".equals(name) || to == SCRIPTED || to == mapId || to <= 0) {
                continue;       // a spawn point, a script, or a hop within the map
            }
            if ("tm".equals(what)) {
                exits.putIfAbsent(name, KnownWorld.mapRef(to));
            } else {
                String arrival = DataTool.getString(portal.getChildByPath("tn"), "");
                if (!arrival.isBlank()) {
                    exits.putIfAbsent(name, arrival);
                }
            }
        }
        return Collections.unmodifiableMap(exits);
    }
}
