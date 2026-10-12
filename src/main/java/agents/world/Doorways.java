package agents.world;

import java.awt.Point;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which of a map's doors can be walked to from a given place in it.
 *
 * <p>A map is not a point. Coming into Swampy Land from Sleepywood puts a character on its
 * bottom floor, and the way on towards Henesys is near the top, reached only from the other
 * side - the one rope up from the bottom does not let anybody off at the top. Routing that
 * took every door in a map to be reachable from every other sent a Bowman into Swampy Land
 * after a bow, found it could go no further, and sent it back, over and over.
 *
 * <p>The answer is the same floors, jumps and ropes the agent walks by ({@link Navigator}),
 * asked once per map and entrance and remembered.
 */
public final class Doorways {

    private Doorways() {
    }

    private static final Map<String, Set<String>> FROM_ENTRANCE = new HashMap<>();

    /** Close enough to a door to be at it, across and up or down. */
    private static final int AT_DOOR = 40;
    private static final int DOOR_FLOATS = 80;

    /**
     * The doors that can be walked to from where a character comes in at this portal, or empty
     * if the entrance is not one the map data knows - in which case nothing is ruled out.
     */
    public static synchronized Optional<Set<String>> fromEntrance(int mapId, String entrance) {
        if (entrance == null) {
            return Optional.empty();
        }
        String key = mapId + "/" + entrance;
        Set<String> known = FROM_ENTRANCE.get(key);
        if (known == null) {
            Optional<Point> at = MapGeometry.portalsIn(mapId).stream()
                    .filter(portal -> portal.name().equals(entrance))
                    .map(MapGeometry.PortalSighting::position)
                    .findFirst();
            if (at.isEmpty()) {
                return Optional.empty();
            }
            known = from(mapId, at.get());
            FROM_ENTRANCE.put(key, known);
        }
        return Optional.of(known);
    }

    /** The doors that can be walked to from this spot. */
    public static Set<String> from(int mapId, Point where) {
        Point feet = Navigator.floorHeight(mapId, where).map(y -> new Point(where.x, y)).orElse(where);
        Set<String> doors = new HashSet<>();
        for (MapGeometry.PortalSighting door : MapGeometry.usablePortalsIn(mapId)) {
            if (canReach(mapId, feet, door.position())) {
                doors.add(door.name());
            }
        }
        return doors;
    }

    /** Whether a door has a place in the map data at all; one that does not is not ruled out. */
    public static boolean isPlaced(int mapId, String door) {
        return MapGeometry.usablePortalsIn(mapId).stream().anyMatch(portal -> portal.name().equals(door));
    }

    private static boolean canReach(int mapId, Point feet, Point door) {
        if (Math.abs(door.x - feet.x) < AT_DOOR && feet.y - door.y < DOOR_FLOATS && door.y - feet.y < AT_DOOR) {
            return true;
        }
        if (MapGeometry.groundIn(mapId).isEmpty()) {
            return true;        // no floor data: nothing to rule anything out with
        }
        return Navigator.nextStep(mapId, feet, door).isPresent();
    }
}
