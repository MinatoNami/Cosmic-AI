package agents.social;

import agents.Mind;
import agents.memory.Belief;
import agents.percept.Observation;
import agents.trace.Trace;
import agents.world.WorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTest {

    @TempDir
    Path traceDir;

    private Mind mind;
    private WorldModel world;

    @BeforeEach
    void setUp() {
        mind = new Mind("Agent0", Trace.toFile(traceDir.resolve("t.jsonl"), "Agent0"));
        world = new WorldModel();
        world.update(new Observation.MapEntered(1, 40000, 0));
        mind.take(new Observation.MapEntered(1, 40000, 0));
    }

    /**
     * The headline: an agent asked why reads back the decision it recorded at the time. It
     * cannot give a reason it did not have, because there is nowhere for one to come from.
     */
    @Test
    void answersWhyFromTheDecisionItRecorded() {
        mind.take(new Observation.MonsterAppeared(2, 9001, 100100, new Point(10, 0)));
        List<String> used = mind.semantic().liveBeliefs().stream().map(Belief::ref).toList();
        mind.decided(2, "hit what is in front of me", "Attack", Map.of("target", 9001), used,
                List.of(), "reflex:wanderer", null);

        String answer = Conversation.answer("why", mind, world).orElseThrow();

        assertTrue(answer.startsWith("Attack because hit what is in front of me"), answer);
        assertTrue(answer.contains("going on:"), "it should name what it was going on: " + answer);
    }

    @Test
    void admitsToHavingDoneNothing() {
        assertEquals("I have not done anything yet.",
                Conversation.answer("why", mind, world).orElseThrow());
    }

    @Test
    void answersWhatItKnowsAboutSomething() {
        mind.take(new Observation.MonsterAppeared(2, 9001, 9300018, new Point(10, 0)));

        String answer = Conversation.answer("know 9300018", mind, world).orElseThrow();

        assertTrue(answer.contains("monster:9300018"), answer);
    }

    @Test
    void saysSoWhenItKnowsNothingAboutSomething() {
        assertTrue(Conversation.answer("know dragons", mind, world).orElseThrow()
                .startsWith("I know nothing about"));
    }

    @Test
    void ignoresThingsThatAreNotQuestionsForIt() {
        assertEquals(Optional.empty(), Conversation.answer("hello everyone", mind, world));
        assertEquals(Optional.empty(), Conversation.answer("", mind, world));
    }

    /** A game message is one line, so answers have to fit in one. */
    @Test
    void keepsAnswersShortEnoughToSay() {
        for (int i = 0; i < 40; i++) {
            mind.take(new Observation.MonsterAppeared(i + 2, 9000 + i, 100100, new Point(i, 0)));
        }
        List<String> used = mind.semantic().liveBeliefs().stream().map(Belief::ref).toList();
        mind.decided(50, "a goal so long it would never fit in a single line of game chat "
                + "and then some more words after that as well", "Attack", Map.of(), used,
                List.of(), "reflex:wanderer", null);

        String answer = Conversation.answer("why", mind, world).orElseThrow();

        assertTrue(answer.length() <= 110, "was " + answer.length() + ": " + answer);
    }

    @Test
    void parsesAClaimAndRejectsChatter() {
        Claim claim = Claim.parse("!know monster:9300018 present_in map:40000").orElseThrow();

        assertEquals("monster:9300018", claim.subject());
        assertEquals("present_in", claim.predicate());
        assertEquals("map:40000", claim.object());
        assertFalse(Claim.parse("hey has anyone seen a snail").isPresent());
        assertFalse(Claim.parse("!know incomplete").isPresent());
    }

    /** What one agent says, another can take - at a lower confidence, and marked as hearsay. */
    @Test
    void whatIsHeardBecomesHearsayNotKnowledge() {
        mind.hear("monster:9300018", "present_in", "map:40000", 5);

        Belief learned = mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals("monster:9300018"))
                .findFirst().orElseThrow();

        assertEquals(Belief.Provenance.HEARSAY, learned.provenance());
        assertFalse(learned.supportedBy().isEmpty(), "hearing it is still evidence of something");

        double heard = learned.confidence();
        mind.take(new Observation.MonsterAppeared(6, 9001, 9300018, new Point(0, 0)));
        double afterSeeing = mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals("monster:9300018"))
                .findFirst().orElseThrow().confidence();

        assertTrue(afterSeeing > heard, "seeing it yourself should count for more than being told");
    }

    @Test
    void announcesABeliefInAFormAnotherAgentCanRead() {
        mind.take(new Observation.MonsterAppeared(2, 9001, 9300018, new Point(10, 0)));
        Belief belief = mind.semantic().liveBeliefs().get(0);

        Claim round = Claim.parse(Claim.announce(belief)).orElseThrow();

        assertEquals(belief.subject(), round.subject());
        assertEquals(belief.object(), round.object());
    }
}
