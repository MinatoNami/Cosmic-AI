package agents.mind;

import agents.percept.Item;
import agents.percept.Observation;
import agents.protocol.ClientPackets;
import agents.world.WorldModel;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Choosing an attack, on the first-job skills as the skill data describes them. */
class FightingTest {

    private static final int BOW = 1452051;
    private static final int ARROWS = 2060000;
    private static final int WAND = 1372005;

    private static WorldModel character(int job, int mp, int weapon, boolean arrows, Map<Integer, Integer> skills) {
        WorldModel world = new WorldModel();
        world.update(new Observation.MapEntered(1, 100000000, 0));
        world.update(new Observation.StatsChanged(1, Map.of("JOB", job, "MP", mp, "MAXMP", 100,
                "LEVEL", 12, "INT", 40, "DEX", 40, "STR", 4, "LUK", 4)));
        List<Item> items = new ArrayList<>();
        items.add(new Item(Item.EQUIP, -11, weapon, 1, Item.EquipStats.NONE));
        if (arrows) {
            items.add(new Item(Item.USE, 1, ARROWS, 500, null));
        }
        world.update(new Observation.InventoryShown(1, 0, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24), items));
        skills.forEach((skill, level) -> world.update(new Observation.SkillChanged(1, skill, level, 0)));
        return world;
    }

    @Test
    void aBowmanWithArrowsShootsWithItsBestSkill() {
        WorldModel bowman = character(300, 50, BOW, true, Map.of(3001004, 1, 3001005, 1));

        Fighting.Attack attack = Fighting.choose(bowman);

        assertEquals(Fighting.Kind.RANGED, attack.kind());
        assertEquals(3001004, attack.skillId(), "Arrow Blow, 190%, over Double Shot's 2 x 92%");
        assertTrue(Fighting.canHit(attack, new Point(0, 0), new Point(300, 20), 120), "from across the screen");
    }

    @Test
    void aBowmanWithoutArrowsCanOnlySwing() {
        WorldModel bowman = character(300, 50, BOW, false, Map.of(3001004, 1));

        assertEquals(Fighting.Kind.MELEE, Fighting.choose(bowman).kind());
    }

    @Test
    void aMagicianCastsMagicClawWhileItHasTheMp() {
        Fighting.Attack attack = Fighting.choose(character(200, 40, WAND, false, Map.of(2001005, 1)));
        assertEquals(Fighting.Kind.MAGIC, attack.kind());
        assertEquals(2, attack.lines());

        assertEquals(0, Fighting.choose(character(200, 5, WAND, false, Map.of(2001005, 1))).skillId(),
                "out of MP: a plain swing");
    }

    @Test
    void keepsUpMagicGuardButNeverHidesInDarkSight() {
        assertTrue(Fighting.buffs(character(200, 40, WAND, false, Map.of(2001002, 1))).stream()
                .anyMatch(buff -> buff.skillId() == 2001002));
        assertTrue(Fighting.buffs(character(400, 40, 1472000, false, Map.of(4001003, 1))).isEmpty());
    }

    /** A plain swing built the new way is byte for byte the swing that has always worked. */
    @Test
    void aPlainSwingIsUnchanged() {
        assertArrayEquals(
                ClientPackets.meleeAttack(1000123, new Point(10, 20), 42, true).getBytes(),
                ClientPackets.attack(Fighting.Kind.MELEE, 0, 1000123, new Point(10, 20), new int[] {42}, true).getBytes());
    }

    @Test
    void aHunterHasABowmansSkillsToo() {
        assertEquals(List.of(0, 300, 310), Fighting.jobsUpTo(310));
        assertEquals(List.of(0, 300), Fighting.jobsUpTo(300));
    }
}
