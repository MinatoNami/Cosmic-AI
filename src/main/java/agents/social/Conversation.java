package agents.social;

import agents.Mind;
import agents.memory.Belief;
import agents.memory.DecisionRecord;
import agents.world.WorldModel;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Lets a person ask an agent a question and get an answer out of its own memory.
 *
 * The one that matters is <strong>why</strong>. An agent answering it reads the decision it
 * recorded at the time - the goal it stated and the beliefs it consulted - rather than being
 * asked to account for itself after the fact. An agent cannot tell you a reason it did not
 * have, because there is nowhere for one to come from.
 *
 * Deliberately not a chat interface. Answers go back through the game as ordinary messages,
 * so they are short, and the vocabulary is small and fixed so that an agent running on
 * reflexes alone can still answer honestly.
 */
public final class Conversation {

    /** A game message is one line; past this the client truncates it anyway. */
    private static final int MAX_REPLY = 110;

    private static final int BELIEFS_IN_ANSWER = 3;

    private Conversation() {
    }

    /**
     * @return what to say back, or empty when the message was not a question for us
     */
    public static Optional<String> answer(String question, Mind mind, WorldModel world) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String asked = question.trim().toLowerCase(Locale.ROOT);

        if (asked.startsWith("why")) {
            return Optional.of(why(mind));
        }
        if (asked.startsWith("where")) {
            return Optional.of(where(world));
        }
        if (asked.startsWith("who")) {
            return Optional.of(who(mind, world));
        }
        if (asked.startsWith("know ")) {
            return Optional.of(about(asked.substring(5).trim(), mind));
        }
        if (asked.startsWith("what do you know about ")) {
            return Optional.of(about(asked.substring(23).trim(), mind));
        }
        if (asked.startsWith("help") || asked.startsWith("?")) {
            return Optional.of("ask me: why / where / who / know <thing>");
        }
        return Optional.empty();
    }

    /** Read straight off the last recorded decision, which is what went into the trace. */
    private static String why(Mind mind) {
        Optional<DecisionRecord> last = mind.lastDecision();
        if (last.isEmpty()) {
            return "I have not done anything yet.";
        }

        DecisionRecord decision = last.get();
        String reason = decision.intent() + " because " + decision.goal();

        List<String> grounds = decision.usedBeliefs().stream()
                .map(ref -> beliefByRef(mind, ref))
                .flatMap(Optional::stream)
                .limit(2)
                .map(Belief::asSentence)
                .toList();

        if (!grounds.isEmpty()) {
            reason += "; going on: " + String.join("; ", grounds);
        }
        return clip(reason);
    }

    private static String where(WorldModel world) {
        return clip("I am in map:" + world.mapId() + " at " + world.selfPosition().x
                + "," + world.selfPosition().y);
    }

    private static String who(Mind mind, WorldModel world) {
        return clip("I am " + mind.name() + ", level " + world.level()
                + ", and I know " + mind.semantic().liveBeliefs().size() + " things.");
    }

    /**
     * Matches loosely on purpose: someone asking will type "9300018" or "monster", not the
     * exact ref an agent happens to store.
     */
    private static String about(String topic, Mind mind) {
        if (topic.isBlank()) {
            return "about what?";
        }

        String answer = mind.semantic().liveBeliefs().stream()
                .filter(b -> b.asSentence().toLowerCase(Locale.ROOT).contains(topic))
                .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                .limit(BELIEFS_IN_ANSWER)
                .map(Belief::asSentence)
                .collect(Collectors.joining("; "));

        return answer.isBlank() ? clip("I know nothing about " + topic) : clip(answer);
    }

    private static Optional<Belief> beliefByRef(Mind mind, String ref) {
        try {
            return mind.semantic().byId(Long.parseLong(ref.substring(1)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String clip(String text) {
        // ASCII only: the client renders anything outside it as a question mark.
        return text.length() <= MAX_REPLY ? text : text.substring(0, MAX_REPLY - 3) + "...";
    }
}
