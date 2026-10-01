package agents.mind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefusalsTest {

    private static final int SHANKS = 22000;

    private final Refusals refusals = new Refusals();

    /** The ferry, refused at every visit while the island taught it nothing new. */
    @Test
    void anOfferRefusedOutOfHabitIsOutgrown() {
        long learntLast = 100;
        refusals.declined(SHANKS, 200, learntLast);
        refusals.declined(SHANKS, 300, learntLast);
        assertFalse(refusals.outgrown(SHANKS, learntLast), "twice is not yet a habit");

        refusals.declined(SHANKS, 400, learntLast);
        assertTrue(refusals.outgrown(SHANKS, learntLast));
    }

    /** Finding something out is a reason to have stayed, so the count starts again. */
    @Test
    void learningSomethingStartsTheCountAgain() {
        refusals.declined(SHANKS, 200, 100);
        refusals.declined(SHANKS, 300, 100);
        refusals.declined(SHANKS, 400, 350);        // found something out at 350

        assertEquals(1, refusals.timesDeclined(SHANKS));
        assertFalse(refusals.outgrown(SHANKS, 350));
    }

    @Test
    void somethingLearntSinceStopsItBeingOutgrown() {
        refusals.declined(SHANKS, 200, 100);
        refusals.declined(SHANKS, 300, 100);
        refusals.declined(SHANKS, 400, 100);

        assertFalse(refusals.outgrown(SHANKS, 450), "it has found something out since");
    }

    @Test
    void sayingYesPutsItBehindIt() {
        for (int t = 200; t <= 400; t += 100) {
            refusals.declined(SHANKS, t, 100);
        }
        refusals.accepted(SHANKS);

        assertFalse(refusals.outgrown(SHANKS, 100));
        assertEquals(0, refusals.timesDeclined(SHANKS));
    }

    @Test
    void oneSomebodysRefusalsSayNothingAboutAnother() {
        for (int t = 200; t <= 400; t += 100) {
            refusals.declined(SHANKS, t, 100);
        }

        assertFalse(refusals.outgrown(2007, 100));
    }
}
