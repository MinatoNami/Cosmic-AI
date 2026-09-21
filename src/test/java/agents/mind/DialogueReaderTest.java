package agents.mind;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueReaderTest {

    @Test
    void readsTheThreeAnswersItAsksFor() {
        assertEquals(1, DialogueReader.parse("CONTINUE").orElseThrow().action());
        assertEquals(0, DialogueReader.parse("DECLINE").orElseThrow().action());

        DialogueReader.Reply chosen = DialogueReader.parse("CHOOSE 2").orElseThrow();
        assertEquals(1, chosen.action());
        assertEquals(2, chosen.selection());
    }

    /**
     * A local reasoning model explains itself however firmly it is told not to, and throwing
     * away a correct decision over a preamble helps nobody.
     */
    @Test
    void findsTheDecisionInsideAnExplanation() {
        String rambling = """
                Okay, so the NPC is offering me a quest to collect snail shells.
                That seems worth doing and costs me nothing up front.
                CONTINUE""";
        assertEquals(1, DialogueReader.parse(rambling).orElseThrow().action());
    }

    @Test
    void picksTheNumberOutOfAChoice() {
        DialogueReader.Reply reply = DialogueReader.parse("I think CHOOSE 3 is right here.").orElseThrow();
        assertEquals(3, reply.selection());
    }

    /** Nothing readable means fall back to the reflex, not send a guess. */
    @Test
    void givesUpOnSomethingItCannotRead() {
        assertTrue(DialogueReader.parse("hmm, hard to say").isEmpty());
        assertTrue(DialogueReader.parse("").isEmpty());
    }

    /** "DECLINE" should not win when the model actually picked an option. */
    @Test
    void prefersAnExplicitChoiceOverAKeywordElsewhere() {
        DialogueReader.Reply reply =
                DialogueReader.parse("Not going to DECLINE outright - CHOOSE 1").orElseThrow();
        assertEquals(1, reply.selection());
        assertEquals(1, reply.action());
    }

    /**
     * Shanks will not take you to Victoria Island below level seven or without 150 mesos.
     * An agent that forgets that the moment the window closes only ever hears it again by
     * accident; one that writes it down has a reason to come back.
     */
    @Test
    void remembersWhatAnNpcSaidItWantsFirst() {
        DialogueReader.Reply reply = DialogueReader.parse(
                "DECLINE\nNEEDS: level 7 and 150 mesos before he will sail").orElseThrow();

        assertEquals(0, reply.action());
        assertEquals("level 7 and 150 mesos before he will sail", reply.needs());
    }

    @Test
    void mostConversationsDemandNothing() {
        assertNull(DialogueReader.parse("CONTINUE").orElseThrow().needs());
        assertNull(DialogueReader.parse("CONTINUE\nNEEDS: none").orElseThrow().needs());
    }
}
