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
        onMapleIsland.update(new Observation.SelfDescribed(1, 2, "Agent0", 1, 0, 104000000, 3));

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
    void readsOwnHealthFromStatUpdates() {
        world.update(new Observation.StatsChanged(1, Map.of("HP", 40, "MAXHP", 50)));

        assertEquals(40, world.hp());
        assertEquals(50, world.maxHp());
    }
}
