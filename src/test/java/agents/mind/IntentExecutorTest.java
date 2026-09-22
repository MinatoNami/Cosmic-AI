package agents.mind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a climbing character lets go of the rope.
 *
 * Riding to the end and walking back is what a route planner does. A character gets off
 * when it is level with what it was heading for, and rides the whole rope only when the
 * thing it wants is still further up.
 */
class IntentExecutorTest {

    @Test
    void getsOffLevelWithWhatItWasHeadingFor() {
        // Up from the floor at y=416. The rope ends at 109, the target sits at 200.
        assertEquals(200, IntentExecutor.stopAt(109, 200, 416));
    }

    @Test
    void ridesTheWholeRopeWhenTheTargetIsStillHigher() {
        // Shanks is at -105, well above where this rope ends. Take all of it and continue.
        assertEquals(109, IntentExecutor.stopAt(109, -105, 416));
    }

    @Test
    void worksTheSameGoingDown() {
        assertEquals(300, IntentExecutor.stopAt(416, 300, 109));
        assertEquals(416, IntentExecutor.stopAt(416, 900, 109),
                "the rope bottoms out at 416; the rest of the way down is a walk or a fall");
    }
}
