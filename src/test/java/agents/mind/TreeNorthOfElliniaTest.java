package agents.mind;

import agents.Mind;
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
    /**
     * Out of the highest room, a few hundred pixels below the instructor with the letter in its
     * bag and urged to explore: it goes on to the instructor, not out of the tree.
     */
    @Test
    void withTheInstructorInSightTheTreeIsNotLeft() {
        Mind mind = new Mind("Test", agents.trace.Trace.toFile(dir.resolve("t.jsonl"), "Test"));
        mind.take(new Observation.MapEntered(1, 101000003, 0));
        mind.take(new Observation.DialogueShown(1, 1032001, "Please get this letter to the instructor.", 0));
        mind.saw("self", "trained_by", "npc:1032001", 1);
        mind.saw("item:4031009", "given_by", "npc:1032001", 1);
        mind.hear("npc:1032001", "sends_you_to", "npc:1072001", 1);
        mind.saw("npc:1072001", "present_in", "map:" + TREE, 1);
        mind.take(new Observation.MapEntered(2, TREE, 0));
        WorldModel world = new WorldModel();
        world.update(new Observation.MapEntered(2, TREE, 0));
        world.movedTo(new Point(-737, -3718));
        world.update(new Observation.StatsChanged(2, java.util.Map.of("LEVEL", 31, "JOB", 200)));
        world.update(new Observation.InventoryShown(2, 1000, java.util.Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24),
                java.util.List.of(new agents.percept.Item(4, 1, 4031009, 1, null))));
        world.update(new Observation.NpcAppeared(2, 7001, 1072001, INSTRUCTOR));
        ReflexPolicy policy = new ReflexPolicy(new java.util.Random(1), Disposition.TALKER);
        policy.urge("door", 500);

        for (int i = 0; i < 20; i++) {
            Policy.Decision decision = policy.decide(mind, world, 3 + i);
            assertTrue(!(decision.intent() instanceof Intent.EnterPortal) && !decision.goal().startsWith("walk to a way out"),
                    "decision " + i + ": " + decision.goal());
        }
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;
    /**
     * Naora Hospital's upper door comes out on Kerning City's upper floor, but nothing in the
     * hospital leads from its lower door up to it: not a way up Kerning City. Agent0 went in and
     * out of it for seventeen hours.
     */
    @Test
    void aRoomWhoseExitCannotBeWalkedToIsNoWayAcross() {
        assertTrue(MapGeometry.roomHopsIn(103000000).stream()
                .anyMatch(hop -> hop.room() == 103000004 && hop.roomExit().equals("out01")), "the data offers it");
        MapGeometry.RoomHop up = MapGeometry.roomHopsIn(103000000).stream()
                .filter(hop -> hop.room() == 103000004 && hop.roomExit().equals("out01")).findFirst().orElseThrow();
        assertTrue(!Navigator.crossableForTest(up), "not crossable going up: " + up);
    }

    /** A room crossing given up on is planned around, not through, for a while. */
    @Test
    void aSideRoomGivenUpOnIsLeftOutOfTheWayUp() {
        MapGeometry.RoomHop first = MapGeometry.roomHopsIn(TREE).stream()
                .filter(hop -> hop.portal().equals("in01") && hop.roomExit().equals("out03")).findFirst().orElseThrow();
        Point foot = Navigator.settleFrom(TREE, new Point(29, 2080)).orElseThrow();
        Point high = Navigator.settleFrom(TREE, new Point(46, -1915)).orElseThrow();
        assertEquals(Navigator.Kind.ROOM, Navigator.nextStep(TREE, foot, high).orElseThrow().kind());

        Navigator.blockRoom(first, 60_000);
        try {
            Optional<Navigator.Step> after = Navigator.nextStep(TREE, foot, high);
            assertTrue(after.isEmpty() || after.get().through() == null
                    || !after.get().through().roomExit().equals("out03") || !after.get().through().portal().equals("in01"),
                    "planned around the room given up on: " + after);
        } finally {
            Navigator.blockRoom(first, -1);
        }
    }
}
