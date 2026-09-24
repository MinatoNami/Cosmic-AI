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

    private void sawMonsterIn(int mapId, int monsterId) {
        believe("monster:" + monsterId, "present_in", KnownWorld.mapRef(mapId));
    }

    /**
     * What a world with nothing left to explore is for.
     *
     * Two agents inherited a map of a hundred and three places and had nowhere to go: their
     * own island had been fully opened by the generation before, and the rest of that map
     * was reached by an NPC warp, which leaves no edge to walk along. Every door fell to the
     * weakest rule there is, which shuffled them between shops and tutorial rooms - and a
     * fighter did no fighting for half an hour. The sightings were already in memory.
     */
    @Test
    void headsForSomewhereItRemembersSomethingLiving() {
        sawDoor(10000, "east00");
        doorLedTo(10000, "east00", 20000);
        sawDoor(20000, "west00");
        doorLedTo(20000, "west00", 10000);
        doorLedTo(20000, "east00", 30000);
        sawMonsterIn(30000, 100100);

        KnownWorld.Route route = world().routeToMonsters(KnownWorld.mapRef(10000))
                .orElseThrow();

        assertEquals("east00", route.firstDoor());
        assertEquals(2, route.hops());
        assertEquals("hunting", route.why());
    }

    /** Where it already is does not count, or it would set off for the room it stands in. */
    @Test
    void doesNotSetOffForTheMonstersUnderItsNose() {
        sawDoor(10000, "east00");
        doorLedTo(10000, "east00", 20000);
        sawMonsterIn(10000, 100100);

        assertTrue(world().routeToMonsters(KnownWorld.mapRef(10000)).isEmpty(),
                "there is nothing to travel for; the fight is right here");
    }

    @Test
    void ignoresSightingsOfThingsThatAreNotMonsters() {
        believe("npc:22000", "present_in", KnownWorld.mapRef(2000000));
        doorLedTo(10000, "east00", 2000000);

        assertTrue(world().routeToMonsters(KnownWorld.mapRef(10000)).isEmpty(),
                "a shopkeeper is not a hunting ground");
    }

    /** Exploring still comes first: an unopened door teaches more than a known monster. */
    @Test
    void prefersAnUnopenedDoorToAKnownHuntingGround() {
        sawDoor(10000, "in00");                     // unopened, right here
        sawDoor(10000, "east00");
        doorLedTo(10000, "east00", 20000);
        sawMonsterIn(20000, 100100);

        KnownWorld world = world();
        assertEquals("frontier",
                world.routeToNearestFrontier(KnownWorld.mapRef(10000)).orElseThrow().why());
        assertEquals("in00",
                world.routeToNearestFrontier(KnownWorld.mapRef(10000)).orElseThrow().firstDoor());
    }

    private void sawNpcIn(int mapId, int npcId) {
        believe("npc:" + npcId, "present_in", KnownWorld.mapRef(mapId));
    }

    private void spokeTo(int mapId, int npcId) {
        remembered.add(new Belief(remembered.size(), "npc:" + npcId, "talks_in",
                KnownWorld.mapRef(mapId), 0.9, Belief.Provenance.FIRST_HAND, List.of(1L),
                0, 0, null, null));
    }

    private void heardAboutConversation(int mapId, int npcId) {
        remembered.add(new Belief(remembered.size(), "npc:" + npcId, "talks_in",
                KnownWorld.mapRef(mapId), 0.5, Belief.Provenance.HEARSAY, List.of(1L),
                0, 0, null, null));
    }

    /**
     * The journey that was missing. Both agents remembered NPCs in Southperry, had never met
     * the one who sells passage off the island, and had no reason to walk back there - every
     * door was opened and no monsters were remembered in that map. They spent half an hour
     * going between two maps instead.
     */
    @Test
    void walksToSomebodyItHasNeverSpokenTo() {
        doorLedTo(10000, "east00", 20000);
        doorLedTo(20000, "east00", 2000000);
        sawNpcIn(2000000, 22000);

        KnownWorld.Route route = world().routeToStrangers(KnownWorld.mapRef(10000))
                .orElseThrow();

        assertEquals("east00", route.firstDoor());
        assertEquals(2, route.hops());
        assertEquals("a stranger", route.why());
    }

    @Test
    void doesNotWalkBackToSomebodyItHasAlreadyMet() {
        doorLedTo(10000, "east00", 20000);
        sawNpcIn(20000, 2100);
        spokeTo(20000, 2100);

        assertTrue(world().routeToStrangers(KnownWorld.mapRef(10000)).isEmpty(),
                "it has heard what that one has to say");
    }

    /**
     * Hearing that a predecessor spoke to somebody is not the same as having met them, and
     * finding out for yourself is the whole point of going.
     */
    @Test
    void anInheritedConversationDoesNotCountAsHavingMetThem() {
        doorLedTo(10000, "east00", 20000);
        sawNpcIn(20000, 2100);
        heardAboutConversation(20000, 2100);

        assertEquals("a stranger",
                world().routeToStrangers(KnownWorld.mapRef(10000)).orElseThrow().why());
    }

    /**
     * Do not cross the island to meet a stranger while one is standing in front of you.
     *
     * An agent spent an afternoon in Southperry with three NPCs in sight - Shanks, who sells
     * the only passage off Maple Island, among them - and its goal the whole time was the
     * door, because a stranger one map away was a reason to travel and a stranger underfoot
     * was not a reason to stay.
     */
    @Test
    void staysPutWhenTheStrangerIsRightHere() {
        doorLedTo(10000, "east00", 20000);
        sawNpcIn(10000, 22000);      // unmet, in this very map
        sawNpcIn(20000, 2100);       // also unmet, one map away

        assertTrue(world().routeToStrangers(KnownWorld.mapRef(10000)).isEmpty(),
                "there is somebody new here; the journey is pointless");
    }

    @Test
    void travelsOnceEverybodyHereHasBeenMet() {
        doorLedTo(10000, "east00", 20000);
        sawNpcIn(10000, 22000);
        spokeTo(10000, 22000);
        sawNpcIn(20000, 2100);

        assertEquals("east00",
                world().routeToStrangers(KnownWorld.mapRef(10000)).orElseThrow().firstDoor());
    }
}
