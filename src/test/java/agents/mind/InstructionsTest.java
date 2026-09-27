package agents.mind;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ids an NPC's own words carry, which every step of a second job is told in. */
class InstructionsTest {

    @Test
    void readsWhoAndWhereFromALetter() {
        Instructions.Heard heard = Instructions.read("Please get this letter to #b#p1072000##k who's around "
                + "#b#m102020300##k near Perion. He is taking care of the job of an instructor in place of me.");

        assertEquals(List.of(1072000), heard.npcs());
        assertEquals(List.of(102020300), heard.maps());
        assertTrue(heard.items().isEmpty());
    }

    @Test
    void readsHowManyOfWhatThroughTheColourMarkup() {
        Instructions.Heard heard = Instructions.read(
                "You will have to collect me #b30 #t4031013##k. Good luck. \r\n#b#L1#I would like to leave#l");

        assertEquals(List.of(new Instructions.ItemAsked(30, 4031013)), heard.items());
    }

    @Test
    void plainWordsNameNothing() {
        assertTrue(Instructions.read("The progress you have made is astonishing.").isEmpty());
    }
}
