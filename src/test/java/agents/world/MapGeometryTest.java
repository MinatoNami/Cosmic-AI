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
}
