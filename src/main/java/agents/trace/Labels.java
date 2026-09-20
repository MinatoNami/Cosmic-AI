package agents.trace;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.MapFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Human names for the ids an agent perceives — {@code map:40000} becomes "Amherst".
 *
 * <strong>These are for the person reading a replay, not for the agent.</strong> An agent's
 * memory holds ids and only ids, and the prompt an LLM policy sees holds ids too. The
 * difference matters: "Potion Shop" and "Blue Snail" carry most of what the agent is
 * supposed to work out for itself, and a model that has read the internet already knows what
 * those words mean. Handing them over would leave the agent looking clever without having
 * learned anything.
 *
 * So labels are written into the trace as their own event kind, by the instrumentation
 * rather than by the agent, and the replay page uses them for display. Nothing in the
 * agent's own reasoning path ever reads this class.
 */
public final class Labels {
    private static final Map<String, String> CACHE = new HashMap<>();

    private Labels() {
    }

    /**
     * @param ref a reference as it appears in a belief triple, such as {@code npc:2101}
     * @return a human name, or null if this kind of reference has no name to give
     */
    public static synchronized String forRef(String ref) {
        if (ref == null || !ref.contains(":")) {
            return null;
        }
        return CACHE.computeIfAbsent(ref, Labels::lookUp);
    }

    private static String lookUp(String ref) {
        String[] parts = ref.split(":", 2);
        int id;
        try {
            id = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return "";
        }

        return switch (parts[0]) {
            case "map" -> mapName(id);
            case "npc" -> stringData("Npc.img", id);
            case "monster" -> stringData("Mob.img", id);
            case "item" -> itemName(id);
            default -> "";
        };
    }

    /**
     * Map names live under an area-dependent path in String.wz, and MapFactory already has
     * the forty lines that work out which. Reusing it reads the same data file the client
     * does and beats copying that logic somewhere it can drift.
     */
    private static String mapName(int mapId) {
        String place = MapFactory.loadPlaceName(mapId);
        String street = MapFactory.loadStreetName(mapId);
        if (place == null || place.isBlank()) {
            return "";
        }
        return street == null || street.isBlank() ? place : street + ": " + place;
    }

    /**
     * Item names are split across several files in String.wz and nested by category, so they
     * are walked once and flattened.
     *
     * ItemInformationProvider knows this layout already, but touching it from the agent
     * process throws at class initialisation - it reaches for server state the moment it
     * loads. Reading the data file is the part we actually want.
     */
    private static synchronized String itemName(int id) {
        if (ITEM_NAMES.isEmpty()) {
            for (String file : ITEM_FILES) {
                try {
                    collectNames(DataProviderFactory.getDataProvider(WZFiles.STRING).getData(file), 0);
                } catch (Exception e) {
                    // Leave what we have; ids still identify the item.
                }
            }
            ITEM_NAMES.putIfAbsent(-1, "");      // so a failed load is not retried every time
        }
        return ITEM_NAMES.getOrDefault(id, "");
    }

    /**
     * v83 splits item names across these, with Eqp and Etc nesting a further level or two
     * of category underneath - hence the walk rather than a direct path.
     */
    private static final String[] ITEM_FILES = {
            "Consume.img", "Eqp.img", "Etc.img", "Ins.img", "Cash.img", "Pet.img"};

    private static final Map<Integer, String> ITEM_NAMES = new HashMap<>();
    private static final int MAX_CATEGORY_DEPTH = 3;

    private static void collectNames(Data node, int depth) {
        if (node == null || depth > MAX_CATEGORY_DEPTH) {
            return;
        }
        for (Data child : node.getChildren()) {
            String name = DataTool.getString(child.getChildByPath("name"), "");
            if (!name.isBlank()) {
                try {
                    ITEM_NAMES.put(Integer.parseInt(child.getName()), name);
                } catch (NumberFormatException ignored) {
                    // a category node, not an item
                }
            } else {
                collectNames(child, depth + 1);
            }
        }
    }

    private static String stringData(String img, int id) {
        try {
            Data data = DataProviderFactory.getDataProvider(WZFiles.STRING).getData(img);
            return DataTool.getString(id + "/name", data, "");
        } catch (Exception e) {
            // A missing name is not worth failing a run over - the id still identifies it.
            return "";
        }
    }
}
