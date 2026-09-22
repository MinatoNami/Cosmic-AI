package agents.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The floor arithmetic, which is all that can be tested without loading Map.wz.
 */
class MapGeometryTest {

    @Test
    void aFlatFloorIsTheSameHeightAllTheWayAlong() {
        MapGeometry.Ground flat = new MapGeometry.Ground(-100, 200, 100, 200);

        assertEquals(200, flat.heightAt(-100));
        assertEquals(200, flat.heightAt(0));
        assertEquals(200, flat.heightAt(100));
    }

    /** Maple Island is full of slopes, and a character walking one should follow it. */
    @Test
    void aSlopedFloorIsInterpolatedAlongItsRun() {
        MapGeometry.Ground slope = new MapGeometry.Ground(0, 100, 100, 200);

        assertEquals(100, slope.heightAt(0));
        assertEquals(150, slope.heightAt(50));
        assertEquals(200, slope.heightAt(100));
    }

    @Test
    void aFloorOnlyCoversItsOwnStretch() {
        MapGeometry.Ground ground = new MapGeometry.Ground(10, 0, 50, 0);

        assertTrue(ground.spans(10));
        assertTrue(ground.spans(30));
        assertTrue(ground.spans(50));
        assertFalse(ground.spans(9));
        assertFalse(ground.spans(51));
    }

    /** Vertical footholds are walls, not floors; they have no single height to stand at. */
    @Test
    void aVerticalFootholdReportsItsTop() {
        MapGeometry.Ground wall = new MapGeometry.Ground(40, 100, 40, 300);

        assertEquals(100, wall.heightAt(40));
    }

    /** Screen y grows downwards, so a rope's "top" is its smaller number. */
    @Test
    void boardsTheEndItIsStandingNearest() {
        MapGeometry.Climb ladder = new MapGeometry.Climb(1576, 109, 416, true);

        assertEquals(416, ladder.endNearest(400), "standing at the foot, it gets on at the foot");
        assertEquals(109, ladder.endAwayFrom(400), "and rides it to the top");
        assertEquals(109, ladder.endNearest(120), "standing at the top, it gets on at the top");
        assertEquals(416, ladder.endAwayFrom(120), "and rides it down");
        assertEquals(307, ladder.height());
    }

    /**
     * Southperry, read from Map.wz, because this is the case that matters: Shanks sells the
     * only passage off Maple Island and stands at y=-105 while an agent arrives at y=500.
     * Something has to say "there is a ladder at x=1576 and it goes up".
     */
    @Test
    void findsTheLadderThatStartsTheClimbToShanks() {
        var climbs = MapGeometry.climbsIn(2000000);

        assertFalse(climbs.isEmpty(), "Southperry has four ropes and ladders in Map.wz");
        MapGeometry.Climb ladder = climbs.stream()
                .filter(c -> c.x() == 1576).findFirst().orElseThrow();
        assertEquals(109, ladder.top());
        assertEquals(416, ladder.bottom());
        assertTrue(ladder.ladder(), "x=1576 is flagged l=1, a ladder rather than a rope");
    }

    @Test
    void offersAClimbThatGetsNearerTheTargetHeight() {
        // Standing on the ground at the east of Southperry, wanting to reach Shanks' deck.
        var towards = MapGeometry.climbTowards(2000000, 1600, 416, -105);

        assertTrue(towards.isPresent(),
                "there is a ladder at x=1576 whose top is 300px closer to Shanks than the floor");
        assertEquals(1576, towards.orElseThrow().x());
    }

    /** Nothing to gain means nothing offered, or an agent would ride ropes for ever. */
    @Test
    void offersNothingWhenAlreadyLevelWithTheTarget()  {
        assertTrue(MapGeometry.climbTowards(2000000, 1600, 416, 420).isEmpty(),
                "a four pixel difference is not worth a ladder");
    }

    /**
     * The trap. Map 1020100 is an empty tutorial staging room: its only portal is a spawn
     * point, which is not somewhere you can go, and it has no ropes and nobody in it. An
     * agent warped there by an NPC has no action available to it at all, and wandered an
     * empty box until someone noticed. Its own returnMap is Split Road of Destiny, which
     * the server applies on login - so the recovery is to log back in.
     */
    @Test
    void knowsAMapWithNoWayOutOfIt() {
        assertTrue(MapGeometry.usablePortalsIn(1020100).isEmpty(),
                "1020100 has one portal and it is a spawn point");
        assertTrue(MapGeometry.climbsIn(1020100).isEmpty(), "and nothing to climb either");
        assertFalse(MapGeometry.usablePortalsIn(1020000).isEmpty(),
                "Split Road of Destiny, where it returns to, does have ways out");
    }
}
