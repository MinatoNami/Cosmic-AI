package agents.mind;

import agents.Mind;
import agents.percept.Observation;
import agents.trace.Trace;
import agents.world.Atlas;
import agents.world.KnownWorld;
import agents.world.MapGeometry;
import agents.world.Navigator;
import agents.world.WorldModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cave of Evil Eye's levels are shafts: entered at the foot, left by a door at the top up a
 * rope the Evil Eyes knock you off. Two agents in their forties spent a night there - one walking
 * at a monster on a ledge it could not reach, the other going down and up between two levels.
 */
class CaveOfEvilEyeTest {

    @TempDir
    Path dir;

    private static final int TREE = 101020000;

    /**
     * Agent1 at the foot of Evil Eye III, with the ropes up set aside after the Evil Eyes knocked
     * it off them: the monster on the ledge above cannot be got to, so it is not gone after.
     */
    @Test
    void aMonsterThatCannotBeReachedIsNotChased() {
        int cave = 105070300;
        Mind mind = new Mind("Test", Trace.toFile(dir.resolve("t.jsonl"), "Test"));
        WorldModel world = new WorldModel();
        world.update(new Observation.MapEntered(1, cave, 0));
        Point foot = Navigator.settleFrom(cave, new Point(170, 1669)).orElseThrow();
        world.movedTo(foot);
        world.update(new Observation.StatsChanged(1, Map.of("LEVEL", 47, "JOB", 110)));
        Point ledge = Navigator.settleFrom(cave, new Point(170, 1318)).orElseThrow();
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, ledge));
        MapGeometry.climbsIn(cave).forEach(rope -> Navigator.blockRope(cave, rope.x(), 60_000));
        try {
            Policy.Decision decision = new ReflexPolicy(new Random(1), Disposition.FIGHTER).decide(mind, world, 3);
            Intent intent = decision.intent();

            boolean afterTheLedge = intent instanceof Intent.MoveTo move && move.destination().equals(ledge)
                    || intent instanceof Intent.Attack attack && attack.objectId() == 9001;
            assertTrue(!afterTheLedge, "not after the one on the ledge at " + ledge + ": " + intent);
        } finally {
            MapGeometry.climbsIn(cave).forEach(rope -> Navigator.blockRope(cave, rope.x(), -1));
        }
    }

    /**
     * At the foot of Evil Eye III with the rope to the top set aside, the door up cannot be
     * reached: no door is taken, rather than the one back down.
     */
    @Test
    void withTheDoorUpOutOfReachTheDoorDownIsNotTaken() throws Exception {
        KnownWorld.useAtlas(new Atlas());
        try {
            int cave = 105070300;
            Mind mind = new Mind("Test", Trace.toFile(dir.resolve("u.jsonl"), "Test"));
            mind.take(new Observation.MapEntered(1, cave, 0));
            WorldModel world = new WorldModel();
            world.update(new Observation.MapEntered(1, cave, 0));
            world.movedTo(new Point(361, 1665));
            world.update(new Observation.StatsChanged(1, Map.of("LEVEL", 47, "JOB", 420)));
            ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.FORAGER);
            policy.decide(mind, world, 2);
            var setOff = ReflexPolicy.class.getDeclaredMethod("setOffFor", String.class, String.class, boolean.class);
            setOff.setAccessible(true);
            setOff.invoke(policy, "map:105070001", "somebody who buys and sells, and you need to", false);

            WorldModel.PortalTarget down = world.portals().stream().filter(p -> p.name().equals("under00")).findFirst().orElseThrow();

            assertEquals(null, policy.pickDoor(List.of(down), mind, cave, "player:1"),
                    "top00 is the way, out of reach for now: under00 is not taken instead");
        } finally {
            KnownWorld.useAtlas(null);
        }
    }
    /**
     * As Agent2 found it: at the foot of Evil Eye III with the ropes up set aside, the way round
     * seen from where it stands is down a level and back up - into the foot of this same shaft.
     * The way on is the door up, out of reach for now, so no door is taken.
     */
    @Test
    void theWayRoundDownAndBackUpIsNotTaken() throws Exception {
        KnownWorld.useAtlas(new Atlas());
        int cave = 105070300;
        MapGeometry.climbsIn(cave).forEach(rope -> Navigator.blockRope(cave, rope.x(), 60_000));
        try {
            Mind mind = new Mind("Test", Trace.toFile(dir.resolve("v.jsonl"), "Test"));
            mind.take(new Observation.MapEntered(1, cave, 0));
            WorldModel world = new WorldModel();
            world.update(new Observation.MapEntered(1, cave, 0));
            world.movedTo(new Point(361, 1665));
            world.update(new Observation.StatsChanged(1, Map.of("LEVEL", 47, "JOB", 420)));
            ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.FORAGER);
            policy.decide(mind, world, 2);
            var setOff = ReflexPolicy.class.getDeclaredMethod("setOffFor", String.class, String.class, boolean.class);
            setOff.setAccessible(true);
            setOff.invoke(policy, "map:105070001", "somebody who buys and sells, and you need to", false);
            WorldModel.PortalTarget down = world.portals().stream().filter(p -> p.name().equals("under00")).findFirst().orElseThrow();

            assertEquals(null, policy.pickDoor(List.of(down), mind, cave, "player:1"),
                    "down a level and back up is no way past the rope");
        } finally {
            MapGeometry.climbsIn(cave).forEach(rope -> Navigator.blockRope(cave, rope.x(), -1));
            KnownWorld.useAtlas(null);
        }
    }
    /**
     * Told to take the boat from Ellinia while deep in the Cave of Evil Eye: the cave's return
     * scroll goes to Sleepywood, much nearer the station than walking out, so it reads one.
     */
    @Test
    void farFromWhereItWasToldToGoAReturnScrollIsQuicker() {
        KnownWorld.useAtlas(new Atlas());
        try {
            assertEquals(105040300, MapGeometry.returnTownOf(105070200), "the cave returns to Sleepywood");
            Mind mind = new Mind("Test", Trace.toFile(dir.resolve("w.jsonl"), "Test"));
            mind.take(new Observation.MapEntered(1, 105070200, 0));
            mind.hear("self", ReflexPolicy.TOLD_TO_TRAVEL_FROM, "map:101000300", 1);
            WorldModel world = new WorldModel();
            world.update(new Observation.MapEntered(1, 105070200, 0));
            world.movedTo(new Point(-284, 1002));
            world.update(new Observation.StatsChanged(1, Map.of("LEVEL", 48, "JOB", 110)));
            ReflexPolicy policy = new ReflexPolicy(new Random(1), Disposition.FIGHTER);
            policy.decide(mind, world, 2);

            assertTrue(policy.readAReturnScroll(), "Sleepywood is the quicker way to Ellinia Station");
            policy.decide(mind, world, 3);
            assertTrue(!policy.readAReturnScroll(), "and not asked again straight away");
        } finally {
            KnownWorld.useAtlas(null);
        }
    }
}
