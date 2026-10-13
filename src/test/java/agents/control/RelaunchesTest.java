package agents.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaunchesTest {

    private static final long MINUTE = 60_000;

    @Test
    void anAgentIsRelaunchedAFewTimesAnHourAndNoMore() {
        Relaunches relaunches = new Relaunches();
        for (int i = 0; i < Relaunches.PER_HOUR; i++) {
            assertFalse(relaunches.exhausted("Agent0", i * MINUTE));
            relaunches.relaunched("Agent0", i * MINUTE);
        }
        assertTrue(relaunches.exhausted("Agent0", 10 * MINUTE));
        assertFalse(relaunches.exhausted("Agent1", 10 * MINUTE), "counted per agent");
    }

    @Test
    void theAllowanceComesBackAsTheHourPasses() {
        Relaunches relaunches = new Relaunches();
        for (int i = 0; i < Relaunches.PER_HOUR; i++) {
            relaunches.relaunched("Agent0", i * MINUTE);
        }
        assertTrue(relaunches.exhausted("Agent0", 59 * MINUTE));
        assertFalse(relaunches.exhausted("Agent0", 60 * MINUTE), "the first one has aged out");
    }

    @Test
    void failingToGetBackInDoesNotUseItUp() {
        Relaunches relaunches = new Relaunches();
        // Asked every minute while the server is down, never relaunched.
        for (int i = 0; i < 10; i++) {
            assertFalse(relaunches.exhausted("Agent0", i * MINUTE));
        }
    }
}
