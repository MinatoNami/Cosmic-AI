package agents.mind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdJobTest {

    @Test
    void eachSecondJobHasItsFamilysChief() {
        assertEquals(2020008, ThirdJob.chiefFor(110), "Tylus, for a Fighter");
        assertEquals(2020009, ThirdJob.chiefFor(230), "Robeira, for a Cleric");
        assertEquals(2020010, ThirdJob.chiefFor(320), "Rene, for a Crossbowman");
        assertEquals(2020011, ThirdJob.chiefFor(420), "Arec, for a Bandit");
        assertEquals(2020013, ThirdJob.chiefFor(520), "Pedro, for a Gunslinger");
    }

    @Test
    void nothingElseHasOne() {
        assertEquals(-1, ThirdJob.chiefFor(0), "a beginner");
        assertEquals(-1, ThirdJob.chiefFor(400), "a first job");
        assertEquals(-1, ThirdJob.chiefFor(421), "a third job already");
        assertEquals(-1, ThirdJob.chiefFor(1110), "a Cygnus Knight");
    }

    @Test
    void isDueAtSeventy() {
        assertFalse(ThirdJob.due(420, 69));
        assertTrue(ThirdJob.due(420, 70));
        assertFalse(ThirdJob.due(400, 70));
    }

    @Test
    void picksTheAdvancementOverTheZakumQuest() {
        String arec = "Anything you want from me?#b\r\n#L0#I want to make the 3th job advancement."
                + "\r\n#L1#Please allow me to do the Zakum Dungeon Quest.";
        assertEquals(0, ThirdJob.optionForAdvancement(arec));
        assertEquals(-1, ThirdJob.optionForAdvancement(
                "Anything you want from me?#b\r\n#L1#Please allow me to do the Zakum Dungeon Quest."));
    }
}
