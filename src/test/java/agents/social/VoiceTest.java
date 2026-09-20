package agents.social;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTest {

    /** No jitter, so the gap is exactly the gap and the test can say when. */
    private Voice voice() {
        return new Voice(Duration.ofSeconds(4), Duration.ZERO, Duration.ofMillis(1200), new Random(1));
    }

    @Test
    void doesNotAnswerInTheSameBreathAsTheQuestion() {
        Voice voice = voice();
        voice.reply("because I owe Roger something", "Watcher", 1_000);

        assertTrue(voice.next(1_000).isEmpty());
        assertTrue(voice.next(2_100).isEmpty(), "still inside the thinking pause");
        assertEquals("because I owe Roger something", voice.next(2_200).orElseThrow().text());
    }

    @Test
    void leavesAGapBetweenTwoThingsSaid() {
        Voice voice = voice();
        voice.announce("first", 0);
        voice.announce("second", 0);

        assertEquals("first", voice.next(0).orElseThrow().text());
        assertTrue(voice.next(3_999).isEmpty(), "the gap has not passed");
        assertEquals("second", voice.next(4_000).orElseThrow().text());
    }

    @Test
    void announcementsGoToTheMapAndTellingsGoToAPerson() {
        Voice voice = voice();
        voice.announce("everyone hears this", 0);
        voice.tell("only you hear this", "Agent1", 0);

        assertEquals(null, voice.next(0).orElseThrow().whisperTo());
        assertEquals("Agent1", voice.next(10_000).orElseThrow().whisperTo());
    }

    /** Being talked at faster than it can answer should cost the stalest question, not the newest. */
    @Test
    void keepsTheNewestWhenTalkedAtFasterThanItCanAnswer() {
        Voice voice = voice();
        for (int i = 0; i < 20; i++) {
            voice.announce("question " + i, 0);
        }
        assertEquals("question 12", voice.next(0).orElseThrow().text());
    }

    @Test
    void saysNothingWhenItHasNothingQueued() {
        Voice voice = voice();
        assertTrue(voice.next(10_000).isEmpty());
        assertFalse(voice.hasSomethingToSay());
    }
}
