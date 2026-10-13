package agents.memory;

import agents.percept.Observation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodicMemoryTest {

    /** Saved before recollections were written as what they were: one wrapper per save. */
    @Test
    void aRecollectionNestedBySavingIsTakenBackToWhatItWas() {
        EpisodicMemory memory = new EpisodicMemory();
        String said = "DialogueShown[tick=5, npcId=2100, text=Hello, style=0]";
        String nested = "Recalled[tick=5, type=Recalled, detail=Recalled[tick=5, type=DialogueShown, detail="
                + said + "]]";

        Episode episode = memory.restore(1, 5, "Recalled", nested);

        Observation.Recalled recalled = (Observation.Recalled) episode.observation();
        assertEquals("DialogueShown", recalled.type());
        assertEquals(said, recalled.detail());
    }
}
