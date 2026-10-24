package agents.mind;

import agents.percept.Observation;
import agents.world.SkillBook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuffClockTest {

    private static final SkillBook.SkillLevel HASTE =
            new SkillBook.SkillLevel(4201003, 5, 10, 0, 0, 0, 0, 0, 50, false);
    private static final long SPEED = 0x8000000000L;

    private final BuffClock clock = new BuffClock();

    private boolean due(long now) {
        return clock.due(List.of(HASTE), now).isPresent();
    }

    @Test
    void aConfirmedCastLastsAsLongAsTheServerSays() {
        clock.cast(HASTE, 0);
        clock.heard(new Observation.BuffGiven(1, HASTE.skillId(), 50_000, 0, SPEED), 100);

        assertFalse(due(30_000), "recast long before it wears off");
        assertTrue(due(50_000), "not recast as it wears off");
    }

    /** Short of MP, the server says nothing: it did not take, and is tried again shortly. */
    @Test
    void aCastNeverConfirmedIsTriedAgainSoon() {
        clock.cast(HASTE, 0);

        assertFalse(due(BuffClock.CONFIRMED_WITHIN_MILLIS + 1), "tried again at once");
        assertTrue(due(BuffClock.CONFIRMED_WITHIN_MILLIS + 1 + BuffClock.FAILED_CAST_RETRY_MILLIS),
                "counted as up for its whole fifty seconds");
    }

    @Test
    void aBuffEndedEarlyGoesBackUpAtOnce() {
        clock.cast(HASTE, 0);
        clock.heard(new Observation.BuffGiven(1, HASTE.skillId(), 50_000, 0, SPEED), 100);

        clock.heard(new Observation.BuffCancelled(2, 0, SPEED), 10_000);

        assertTrue(due(10_000));
    }

    /** Recasting ends the cast before it, and the server says so; that is not the buff ending. */
    @Test
    void theCancelThatComesWithARecastIsNotTheBuffEnding() {
        clock.cast(HASTE, 0);
        clock.heard(new Observation.BuffGiven(1, HASTE.skillId(), 50_000, 0, SPEED), 100);

        clock.heard(new Observation.BuffCancelled(2, 0, SPEED), 600);

        assertFalse(due(10_000));
    }

    @Test
    void aCancelOfOtherStatsLeavesItBe() {
        clock.cast(HASTE, 0);
        clock.heard(new Observation.BuffGiven(1, HASTE.skillId(), 50_000, 0, SPEED), 100);

        clock.heard(new Observation.BuffCancelled(2, 0x4L, 0), 10_000);

        assertFalse(due(10_000));
    }

    @Test
    void dyingTakesEveryBuff() {
        clock.cast(HASTE, 0);
        clock.heard(new Observation.BuffGiven(1, HASTE.skillId(), 50_000, 0, SPEED), 100);

        clock.died();

        assertEquals(HASTE, clock.due(List.of(HASTE), 1000).orElseThrow());
    }
}
