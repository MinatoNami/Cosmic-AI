package agents.world;

import agents.memory.Belief;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownWorldTest {

    private final List<Belief> remembered = new ArrayList<>();

    private void sawDoor(int mapId, String door) {
        believe(KnownWorld.mapRef(mapId), "has_door", door);
    }

    private void doorLedTo(int mapId, String door, int destination) {
        believe(KnownWorld.portalRef(mapId, door), "leads_to", KnownWorld.mapRef(destination));
    }

    private void doorLedNowhere(int mapId, String door) {
        believe(KnownWorld.portalRef(mapId, door), "leads_to", KnownWorld.NOWHERE);
    }

    private void believe(String subject, String predicate, String object) {
        remembered.add(new Belief(remembered.size(), subject, predicate, object, 0.9,
                Belief.Provenance.FIRST_HAND, List.of(1L), 0, 0, null, null));
    }

    private KnownWorld world() {
        return KnownWorld.rememberedBy(remembered);
    }

    /**
     * The whole reason this class exists.
     *
     * Three maps in a line. Every door in the first two has been opened; the third has one
     * that has not. Standing in the first map, nothing in the room says anything is missing,
     * and the agent still has to know to set off east.
     */
    @Test
    void findsTheDoorItNeverOpenedTwoMapsAway() {
        sawDoor(10000, "east00");
        doorLedTo(10000, "east00", 20000);
        sawDoor(20000, "west00");
        sawDoor(20000, "east00");
        doorLedTo(20000, "west00", 10000);
        doorLedTo(20000, "east00", 30000);
        sawDoor(30000, "west00");
        doorLedTo(30000, "west00", 20000);
        sawDoor(30000, "south00");      // seen, never opened

        KnownWorld.Route route = world().routeToNearestFrontier(KnownWorld.mapRef(10000))
                .orElseThrow();

        assertEquals("east00", route.firstDoor());
        assertEquals(2, route.hops());
        assertEquals(KnownWorld.mapRef(30000), route.towards());
    }

    @Test
    void takesTheUnopenedDoorInThisRoomBeforeTravellingForOne() {
        sawDoor(10000, "east00");
        sawDoor(10000, "in00");
        doorLedTo(10000, "east00", 20000);
        sawDoor(20000, "north00");

        KnownWorld.Route route = world().routeToNearestFrontier(KnownWorld.mapRef(10000))
                .orElseThrow();

        assertEquals("in00", route.firstDoor());
        assertEquals(0, route.hops());
    }

    @Test
    void hasNowhereToGoWhenEveryDoorItHasSeenIsOpened() {
        sawDoor(10000, "east00");
        doorLedTo(10000, "east00", 20000);
        sawDoor(20000, "west00");
        doorLedTo(20000, "west00", 10000);

        assertTrue(world().routeToNearestFrontier(KnownWorld.mapRef(10000)).isEmpty(),
                "a closed loop with no unopened doors has no frontier to walk to");
    }

    /** Fewest doors, not the first path stumbled on. */
    @Test
    void takesTheShortWayRound() {
        sawDoor(10000, "long00");
        sawDoor(10000, "short00");
        doorLedTo(10000, "long00", 20000);
        doorLedTo(20000, "on00", 40000);
        doorLedTo(10000, "short00", 30000);
        doorLedTo(30000, "on00", 40000);
        sawDoor(40000, "secret00");

        KnownWorld.Route route = world().routeToNearestFrontier(KnownWorld.mapRef(10000))
                .orElseThrow();

        assertEquals(2, route.hops());
    }

    @Test
    void walksToAParticularMapWhenItHasAnErrandThere() {
        doorLedTo(10000, "east00", 20000);
        doorLedTo(20000, "east00", 30000);

        KnownWorld.Route route = world()
                .routeTo(KnownWorld.mapRef(10000), KnownWorld.mapRef(30000))
                .orElseThrow();

        assertEquals("east00", route.firstDoor());
        assertEquals(2, route.hops());
        assertEquals(KnownWorld.mapRef(30000), route.towards());
    }

    @Test
    void willNotRouteThroughDoorsItHasNeverOpened() {
        sawDoor(10000, "east00");       // seen, unopened - not a road it can plan along

        assertTrue(world().routeTo(KnownWorld.mapRef(10000), KnownWorld.mapRef(20000)).isEmpty(),
                "a door of unknown destination is somewhere to go, not a way through");
    }

    /**
     * A door that timed out once and worked every time after was being written off for good.
     *
     * {@code leads_to} admits more than one value at a time, so both verdicts sit in memory,
     * and reading whichever was recorded first meant one slow map change condemned a working
     * door permanently. Having seen where a door goes is evidence; having once failed to
     * notice is not.
     */
    @Test
    void believesWhereADoorWentOverAnEarlierVerdictOfNothing() {
        doorLedNowhere(10000, "east00");
        doorLedTo(10000, "east00", 20000);

        assertEquals(Optional.of(KnownWorld.mapRef(20000)),
                world().destinationOf(KnownWorld.portalRef(10000, "east00")));
    }

    @Test
    void stillCallsADoorADudWhenThatIsAllItHasEverSeen() {
        doorLedNowhere(10000, "tuto00");

        assertEquals(Optional.of(KnownWorld.NOWHERE),
                world().destinationOf(KnownWorld.portalRef(10000, "tuto00")));
    }

    @Test
    void saysNothingAboutADoorItHasNeverTried() {
        sawDoor(10000, "east00");

        assertEquals(Optional.empty(),
                world().destinationOf(KnownWorld.portalRef(10000, "east00")));
    }

    /**
     * A shop entrance is a door that works and leads somewhere the agent has been, which is
     * exactly what the last resort settles for - so an agent with nothing better to do went
     * into a shop, came out, and did it again for hours, in two different towns.
     */
    @Test
    void knowsWhenARoomHasNothingLeftInIt() {
        sawDoor(1000001, "out00");                  // a shop: one way out
        doorLedTo(1000001, "out00", 1000000);

        assertTrue(world().isSpentRoom(KnownWorld.mapRef(1000001)));
    }

    @Test
    void aRoomWithAnUnopenedDoorIsNotSpent() {
        sawDoor(1000001, "out00");
        sawDoor(1000001, "in00");                   // a back room it has never opened
        doorLedTo(1000001, "out00", 1000000);

        assertFalse(world().isSpentRoom(KnownWorld.mapRef(1000001)));
    }

    /** Never having been somewhere is not the same as having seen all of it. */
    @Test
    void somewhereItHasNeverEnteredIsNotARoom() {
        assertFalse(world().isSpentRoom(KnownWorld.mapRef(1000001)));
    }

    @Test
    void aTownWithSeveralExitsIsNotARoom() {
        sawDoor(1000000, "east00");
        sawDoor(1000000, "west00");
        doorLedTo(1000000, "east00", 20000);
        doorLedTo(1000000, "west00", 50000);

        assertFalse(world().isSpentRoom(KnownWorld.mapRef(1000000)));
    }
}
