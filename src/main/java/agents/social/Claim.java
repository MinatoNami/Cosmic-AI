package agents.social;

import agents.memory.Belief;

import java.util.Optional;

/**
 * A belief said out loud, so another agent can pick it up.
 *
 * Agents share nothing in memory, so anything one knows reaches another only by being said
 * in the world and heard. A fixed form makes that possible without pretending the listener
 * understood English: {@code !know monster:9300018 present_in map:40000}.
 *
 * The listener does not have to believe it. What arrives becomes a belief with
 * {@link Belief.Provenance#HEARSAY}, which starts at lower confidence than anything seen,
 * and the episode behind it is the moment of hearing - so a replay shows who said it and
 * when, and first-hand corroboration later overtakes it naturally.
 */
public record Claim(String subject, String predicate, String object) {

    private static final String PREFIX = "!know ";

    public static String announce(Belief belief) {
        return PREFIX + belief.subject() + " " + belief.predicate() + " " + belief.object();
    }

    public static Optional<Claim> parse(String text) {
        if (text == null || !text.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = text.substring(PREFIX.length()).trim().split("\\s+", 3);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Claim(parts[0], parts[1], parts[2]));
    }
}
