package agents.mind;

import agents.percept.Observation;
import agents.world.MapGeometry;
import agents.world.Navigator;
import agents.world.WorldModel;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Magician's instructor stands at the top of the tree north of Ellinia, and the tree is
 * climbed through small rooms inside its trunk whose doors come out higher up. A Magician with
 * Grendel's letter stood at its foot for three hours: no floor or rope went up, and the rooms
 * were only somewhere to go and come back from.
 */
class TreeNorthOfElliniaTest {

    private static final int TREE = 101020000;
    private static final Point INSTRUCTOR = new Point(-32, -4113);

    @Test
    void fromTheFootOfTheTreeTheInstructorCanBeReachedAndSpokenTo() {
        WorldModel world = new WorldModel();
        world.update(new Observation.MapEntered(1, TREE, 0));
        Point at = Navigator.settleFrom(TREE, new Point(0, 1500)).orElseThrow();
        Point stand = ReflexPolicy.approach(world, at, INSTRUCTOR);

        int rooms = 0;
        for (int i = 0; i < 40; i++) {
            Optional<Navigator.Step> step = Navigator.nextStep(TREE, at, stand);
            assertTrue(step.isPresent(), "lost the way at " + at);
            if (step.get().kind() == Navigator.Kind.ROOM) {
                rooms++;
            }
            at = step.get().landing();
            if (step.get().kind() == Navigator.Kind.WALK) {
                break;
            }
        }

        assertTrue(rooms > 0, "the way up goes through the trunk");
        assertTrue(IntentExecutor.canSpeakTo(TREE, at, INSTRUCTOR, IntentExecutor.SPEAKING_DISTANCE),
                "within speaking distance at " + at);
    }

    /** Inside each room on the way up, from the door in to the door that goes highest. */
    @Test
    void eachRoomInTheTrunkCanBeClimbed() {
        assertCrossable(101020001, "out00", "out03");
        assertCrossable(101020007, "out00", "out01");
        assertCrossable(101020009, "out00", "out01");
    }

    private static void assertCrossable(int room, String in, String out) {
        Point from = door(room, in);
        Point to = door(room, out);
        Point at = Navigator.settleFrom(room, from).orElse(from);
        for (int i = 0; i < 80; i++) {
            Optional<Navigator.Step> step = Navigator.nextStep(room, at, to);
            assertTrue(step.isPresent(), "room " + room + ": lost the way at " + at);
            at = step.get().landing();
            if (step.get().kind() == Navigator.Kind.WALK) {
                break;
            }
        }
        assertTrue(at.distance(to) < 60, "room " + room + ": reached " + out + " from " + in + ", at " + at);
    }

    private static Point door(int room, String name) {
        return MapGeometry.portalsIn(room).stream().filter(p -> p.name().equals(name))
                .map(MapGeometry.PortalSighting::position).findFirst().orElseThrow();
    }

    @Test
    void theTrunksRoomsAreWaysUpTheTree() {
        assertTrue(MapGeometry.roomHopsIn(TREE).stream()
                .anyMatch(hop -> hop.portal().equals("in01") && hop.roomExit().equals("out03")));
        assertEquals(0, MapGeometry.roomHopsIn(100000000).stream()
                .filter(hop -> hop.room() == 100000100).count(), "a town's shops are not side rooms");
    }
}
