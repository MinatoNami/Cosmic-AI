package agents.mind;

import agents.percept.Observation;
import agents.world.MapGeometry;
import agents.world.Navigator;
import agents.world.WorldModel;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the agent moves, on real maps read from Map.wz, checked the way someone watching would.
 *
 * What people saw before: agents walking off the ends of platforms and across the map through
 * the air, cutting through slopes, and popping a hundred pixels straight up onto ledges. So
 * these assert what must hold at every step - standing on a floor or holding a rope, and never
 * higher than a jump could have taken it - as well as that it gets where it is going.
 */
class IntentExecutorTest {

    /** Map 50000: a lower floor ending at x=861, and a door up a flight of platforms. */
    private static final int STEPPED_MAP = 50000;
    private static final int SOUTHPERRY = 2000000;

    private final List<Object> sent = new ArrayList<>();
    private final IntentExecutor executor = new IntentExecutor(sent::add);

    private WorldModel standingAt(int map, Point at) {
        WorldModel world = new WorldModel();
        world.update(new Observation.MapEntered(1, map, 0));
        world.movedTo(at);
        return world;
    }

    /** Walks towards a target, checking every step, and returns where it ended up. */
    private Point walkTowards(WorldModel world, Point target, int decisions) {
        int map = world.mapId();
        Point before = world.selfPosition();
        for (int i = 0; i < decisions && world.selfPosition().distance(target) > 8; i++) {
            executor.execute(new Intent.MoveTo(target), world);
            Point now = world.selfPosition();
            assertTrue(onAFloor(map, now) || onARope(map, now),
                    "ended a move in mid-air at " + now + " (from " + before + ")");
            if (!onARope(map, now) && !onARope(map, before)) {
                assertTrue(before.y - now.y <= 76,
                        "rose " + (before.y - now.y) + "px in one move, " + before + " -> " + now);
            }
            before = now;
        }
        return world.selfPosition();
    }

    private static boolean onAFloor(int map, Point at) {
        return Navigator.floorHeight(map, at).map(floor -> Math.abs(floor - at.y) <= 1).orElse(false);
    }

    private static boolean onARope(int map, Point at) {
        return MapGeometry.climbsIn(map).stream()
                .anyMatch(rope -> rope.x() == at.x && at.y >= rope.top() - 60 && at.y <= rope.bottom() + 60);
    }

    /** Agent2's case: from the lower floor to the door at (1430, 269), up two jumps. */
    @Test
    void climbsTheStepsToADoorRatherThanPoppingUpToIt() {
        WorldModel world = standingAt(STEPPED_MAP, new Point(420, 395));

        Point end = walkTowards(world, new Point(1430, 269), 80);

        assertTrue(end.distance(new Point(1430, 275)) <= 8, "never reached the door: " + end);
    }

    /** Aimed at a point with no floor under it, it goes to the end of its own floor and stops there. */
    @Test
    void doesNotWalkOffTheEndOfItsFloor() {
        WorldModel world = standingAt(STEPPED_MAP, new Point(420, 395));

        Point end = walkTowards(world, new Point(1367, 395), 40);

        assertEquals(861, end.x, "the lower floor ends at x=861; it walked on through the air to " + end);
        assertEquals(395, end.y);
    }

    /** Left in mid-air by the old walking - Agent2 was found at (1367, 395) - it comes down first. */
    @Test
    void anAgentInMidAirComesDownOntoAFloor() {
        WorldModel world = standingAt(STEPPED_MAP, new Point(1367, 395));

        executor.execute(new Intent.MoveTo(new Point(1430, 269)), world);

        assertTrue(onAFloor(STEPPED_MAP, world.selfPosition()), "still floating at " + world.selfPosition());
    }

    /** Southperry: up the ladder at x=1576 from the ground, and back down, without a jump the ladder should have done. */
    @Test
    void takesTheLadderUpAndDropsBackDown() {
        WorldModel world = standingAt(SOUTHPERRY, new Point(1500, 416));

        Point top = walkTowards(world, new Point(1600, 107), 60);
        assertTrue(top.distance(new Point(1600, 107)) <= 8, "never got up the ladder: " + top);

        Point bottom = walkTowards(world, new Point(300, 527), 80);
        assertTrue(bottom.distance(new Point(300, 527)) <= 8, "never got back down: " + bottom);
    }

    /** Every packet a step sends is one movement; walking a long way is many small ones. */
    @Test
    void walksInStepsRatherThanOneLongSlide() {
        WorldModel world = standingAt(SOUTHPERRY, new Point(300, 527));

        executor.execute(new Intent.MoveTo(new Point(1200, 527)), world);

        assertEquals(1, sent.size());
        assertTrue(world.selfPosition().x - 300 <= 75, "one decision carried it " + (world.selfPosition().x - 300) + "px");
    }

    /**
     * Right Around Lith Harbor. The door to Lith Harbor is up a slope that only a 71-pixel jump
     * reaches; with jumps capped at 70 there was no route, so an agent heading there gave up
     * and took the door the other way, back and forth between two maps.
     */
    @Test
    void findsTheWayUpToTheDoorToLithHarbor() {
        WorldModel world = standingAt(104000100, new Point(2400, 395));

        Point end = walkTowards(world, new Point(-487, 287), 200);

        assertTrue(end.distance(new Point(-487, 287)) <= 8, "never reached west00: " + end);
    }

    /**
     * Walking takes several decisions now, so a swing sent after one step at something across
     * the floor was a swing from 2,500px away, which the server logs as a distance hack.
     */
    @Test
    void walksUpToAMonsterBeforeSwingingAtIt() {
        WorldModel world = standingAt(SOUTHPERRY, new Point(300, 527));

        executor.execute(new Intent.Attack(9001, new Point(800, 527)), world);
        assertEquals(1, sent.size(), "one step towards it, and no swing from out of reach");

        sent.clear();
        world.movedTo(new Point(760, 527));
        executor.execute(new Intent.Attack(9001, new Point(800, 527)), world);
        assertEquals(2, sent.size(), "close enough now: step in and swing");
    }

    /**
     * The way into Henesys from A Hill West of Henesys: up a ladder, then a thirty-pixel step
     * onto the floor with the door. The step was not counted as a jump, so there was no route,
     * and no agent had ever stood in Henesys - where the bowman instructor is.
     */
    @Test
    void findsTheWayToTheDoorIntoHenesys() {
        WorldModel world = standingAt(104030000, new Point(-2300, -115));

        Point end = walkTowards(world, new Point(100, -355), 400);

        assertTrue(end.distance(new Point(100, -355)) <= 8, "never reached east00: " + end);
    }
}
