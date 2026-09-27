package agents.body;

import agents.percept.Observation;
import agents.world.WorldModel;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TouchTest {

    private static final int SNAIL = 100100;
    private static final int HARMLESS = 9999999;

    private final Touch touch = new Touch(new Random(1), id -> id == SNAIL
            ? new Touch.MobBody(12, 1, true)
            : new Touch.MobBody(0, 1, false));

    private final WorldModel world = new WorldModel((map, portal) -> Optional.empty());

    private void standAt(int x, int y) {
        world.update(new Observation.StatsChanged(1, Map.of("HP", 50, "MAXHP", 50, "LEVEL", 1)));
        world.movedTo(new Point(x, y));
    }

    @Test
    void aMonsterStandingOnTheAgentHurtsIt() {
        standAt(100, 0);
        world.update(new Observation.MonsterAppeared(2, 9001, SNAIL, new Point(110, 0)));

        Touch.Contact contact = touch.check(world, 0, 0).orElseThrow();

        assertEquals(9001, contact.objectId());
        assertEquals(SNAIL, contact.monsterId());
        assertTrue(contact.damage() >= 7 && contact.damage() <= 10, "a snail, around eight: " + contact.damage());
        assertTrue(contact.facingLeft(), "knocked away from a monster on its right");
    }

    @Test
    void aMonsterAcrossTheMapDoesNot() {
        standAt(100, 0);
        world.update(new Observation.MonsterAppeared(2, 9001, SNAIL, new Point(400, 0)));

        assertFalse(touch.check(world, 0, 0).isPresent());
    }

    /** The flashing moment after a hit, or a monster standing still would hit every tick. */
    @Test
    void cannotBeHitAgainStraightAway() {
        standAt(100, 0);
        world.update(new Observation.MonsterAppeared(2, 9001, SNAIL, new Point(100, 0)));

        assertTrue(touch.check(world, 0, 0).isPresent());
        assertFalse(touch.check(world, 0, 1000).isPresent());
        assertTrue(touch.check(world, 0, Touch.INVINCIBLE_MILLIS).isPresent());
    }

    @Test
    void somethingWhoseBodyDoesNoHarmDoesNone() {
        standAt(100, 0);
        world.update(new Observation.MonsterAppeared(2, 9001, HARMLESS, new Point(100, 0)));

        assertFalse(touch.check(world, 0, 0).isPresent());
    }

    @Test
    void theDeadFeelNothing() {
        standAt(100, 0);
        world.update(new Observation.MonsterAppeared(2, 9001, SNAIL, new Point(100, 0)));
        world.update(new Observation.StatsChanged(3, Map.of("HP", 0)));

        assertFalse(touch.check(world, 0, 0).isPresent());
    }

    @Test
    void defenceAndOutlevellingSoftenTheBlowButNeverToNothing() {
        Touch.MobBody snail = new Touch.MobBody(12, 1, true);

        int bare = touch.damage(snail, 0, 1);
        int armoured = touch.damage(snail, 30, 1);

        assertTrue(bare >= 7);
        assertEquals(1, armoured, "more defence than the blow still costs one");
    }
}
