package agents.world;

import agents.percept.Observation;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldModelTest {

    private final WorldModel world = new WorldModel();

    @Test
    void tracksMonstersUntilTheyDie() {
        world.update(new Observation.MonsterAppeared(1, 9001, 100100, new Point(10, 0)));
        assertTrue(world.nearestMonster().isPresent());

        world.update(new Observation.MonsterDied(2, 9001));
        assertFalse(world.nearestMonster().isPresent());
    }

    @Test
    void picksTheClosestOfSeveral() {
        world.movedTo(new Point(0, 0));
        world.update(new Observation.MonsterAppeared(1, 9001, 100100, new Point(500, 0)));
        world.update(new Observation.MonsterAppeared(2, 9002, 100100, new Point(30, 0)));

        assertEquals(9002, world.nearestMonster().orElseThrow().objectId());
    }

    @Test
    void followsAMonsterAsItMoves() {
        world.update(new Observation.MonsterAppeared(1, 9001, 100100, new Point(10, 0)));
        world.update(new Observation.ThingMoved(2, 9001, new Point(200, 0)));

        assertEquals(new Point(200, 0), world.nearestMonster().orElseThrow().position());
    }

    /**
     * Everything visible belongs to the map it was seen in. Carrying it across would have the
     * agent chasing a monster that is no longer anywhere near it.
     */
    @Test
    void forgetsTheOldMapOnArrivingInANewOne() {
        world.update(new Observation.MapEntered(1, 10000, 0));
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(10, 0)));
        world.update(new Observation.PlayerAppeared(3, 5, "Someone", 1));

        world.update(new Observation.MapEntered(4, 104000000, 0));

        assertFalse(world.nearestMonster().isPresent());
        assertTrue(world.visiblePlayers().isEmpty());
        assertEquals(104000000, world.mapId());
    }

    @Test
    void reentryToTheSameMapKeepsWhatIsThere() {
        world.update(new Observation.MapEntered(1, 10000, 0));
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(10, 0)));
        world.update(new Observation.MapEntered(3, 10000, 1));

        assertTrue(world.nearestMonster().isPresent());
    }

    /** Map 104000000 has portal 3 at (720, 150); nothing else is known. */
    private final WorldModel onMapleIsland = new WorldModel((mapId, portalId) ->
            mapId == 104000000 && portalId == 3 ? Optional.of(new Point(720, 150)) : Optional.empty());

    /**
     * Nothing echoes an agent's own movement back to it, so arriving somewhere is the one
     * time it is told where it stands. It used to keep the coordinates of the map it left.
     */
    @Test
    void arrivingPutsTheAgentOnThePortalItCameOutOf() {
        onMapleIsland.movedTo(new Point(-900, 400));

        onMapleIsland.update(new Observation.MapEntered(1, 104000000, 3));

        assertEquals(new Point(720, 150), onMapleIsland.selfPosition());
    }

    @Test
    void enteringTheWorldPutsTheAgentWhereItLoggedIn() {
        onMapleIsland.update(new Observation.SelfDescribed(1, 2, "Agent0", 1, 0, 104000000, 3, Map.of()));

        assertEquals(new Point(720, 150), onMapleIsland.selfPosition());
    }

    @Test
    void anUnknownPortalLeavesThePositionAlone() {
        onMapleIsland.movedTo(new Point(-900, 400));

        onMapleIsland.update(new Observation.MapEntered(1, 104000000, 99));

        assertEquals(new Point(-900, 400), onMapleIsland.selfPosition());
    }

    @Test
    void aMonsterThatVanishesIsNoLongerThere() {
        world.update(new Observation.MonsterAppeared(1, 9001, 100100, new Point(10, 0)));

        world.update(new Observation.MonsterVanished(2, 9001));

        assertFalse(world.nearestMonster().isPresent());
    }

    @Test
    void remembersHowHurtAMonsterIsUntilItGoes() {
        world.update(new Observation.MonsterAppeared(1, 9001, 100100, new Point(10, 0)));
        world.update(new Observation.MonsterHurt(2, 9001, 40));

        assertEquals(Optional.of(40), world.monsterHealth(9001));

        world.update(new Observation.MonsterDied(3, 9001));
        assertTrue(world.monsterHealth(9001).isEmpty());
    }

    /** Health used to be unknown until it first changed, which for an agent never hit was never. */
    @Test
    void knowsItsOwnNumbersFromTheMomentItArrives() {
        onMapleIsland.update(new Observation.SelfDescribed(1, 2, "Agent0", 3, 0, 104000000, 3,
                Map.of("HP", 50, "MAXHP", 50, "MP", 5, "MAXMP", 5, "STR", 12)));

        assertEquals(50, onMapleIsland.hp());
        assertEquals(12, onMapleIsland.stat("STR"));
        assertEquals(5, onMapleIsland.stat("MAXMP"));
        assertEquals(0, onMapleIsland.job());
        assertFalse(onMapleIsland.isDead());
    }

    @Test
    void isDeadAtNoHealth() {
        world.update(new Observation.StatsChanged(1, Map.of("HP", 0)));

        assertTrue(world.isDead());
    }

    @Test
    void readsOwnHealthFromStatUpdates() {
        world.update(new Observation.StatsChanged(1, Map.of("HP", 40, "MAXHP", 50)));

        assertEquals(40, world.hp());
        assertEquals(50, world.maxHp());
    }

    /**
     * A fighter with a full bag tried to pick the same drop up 262 times. Once told the bag
     * is full it takes only money, which needs no room, and tries an item again later.
     */
    @Test
    void aFullBagLeavesItemsWhereTheyLieButStillTakesMoney() {
        WorldModel world = new WorldModel();
        world.update(new Observation.MapEntered(1, 10000, 0));
        world.movedTo(new java.awt.Point(0, 0));
        world.update(new Observation.DropAppeared(2, 501, 4000000, false, new java.awt.Point(10, 0)));
        world.update(new Observation.DropAppeared(2, 502, 160, true, new java.awt.Point(90, 0)));

        assertEquals(501, world.nearestDropWorthTaking(3).orElseThrow().objectId());

        world.update(new Observation.InventoryFull(4));
        assertEquals(502, world.nearestDropWorthTaking(5).orElseThrow().objectId(),
                "the bag is full, so the item is no use; the mesos still are");

        assertEquals(501, world.nearestDropWorthTaking(4 + 1000).orElseThrow().objectId(),
                "long enough later it is worth trying again");
    }
}
