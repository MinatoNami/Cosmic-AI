package agents.mind;

import agents.percept.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Reads what an NPC actually said and decides what to say back.
 *
 * Until now an agent answered from the style byte alone - yes to anything that asked, an
 * acknowledgement to anything that did not - without reading a word. That is enough to get
 * through a conversation and no use at all for deciding whether to be in one, and it meant an
 * agent could accept a quest, decline a reward and agree to be teleported somewhere with the
 * same blank cheerfulness.
 *
 * <p>Worth being explicit about why this does not break the rule {@link agents.trace.Labels}
 * exists to enforce. That rule keeps names the agent was never told out of its reasoning -
 * "Blue Snail" is data the instrumentation has and the agent has not earned. An NPC's words
 * arrive in a packet addressed to the agent. It is being spoken to, and reading what you are
 * told is perception, not a hint from outside the world. What it makes of the words is its
 * own problem, which is the point.
 *
 * <p>The reply is deliberately narrow: continue, decline, or pick an option. Nothing here
 * types free text at an NPC, because the server's dialogue protocol has no room for it.
 */
public class DialogueReader {
    private static final Logger log = LoggerFactory.getLogger(DialogueReader.class);

    private static final String SYSTEM = """
            You are playing a character in a 2D fantasy MMO. An NPC is talking to you and the \
            client is waiting for one answer.

            You know nothing about this world except what you have seen and what you are being \
            told right now. Decide from the words themselves.

            Answer with exactly one line and nothing else:
              CONTINUE   - agree, accept, or move the conversation on
              DECLINE    - refuse, or end the conversation
              CHOOSE <n> - pick option n, counting from 0, when a list is offered

            Prefer CONTINUE when the NPC is offering you something, work, or information. \
            Prefer DECLINE when it would cost you something you cannot judge, or when the \
            conversation is over.""";

    /** What to send back: the action byte, and a menu selection when one was asked for. */
    public record Reply(byte action, int selection, String why) {
        public static final int NO_SELECTION = -1;
    }

    /** Continue, which is what the style-byte reflex always did. */
    public static final Reply CONTINUE = new Reply((byte) 1, Reply.NO_SELECTION, "reflex: keep talking");

    private final Oracle oracle;

    public DialogueReader(Oracle oracle) {
        this.oracle = oracle;
    }

    /**
     * @return what to say back, or empty when the model could not be reached - the caller
     *         should fall back to the reflex rather than leave the NPC hanging
     */
    public Optional<Reply> read(Observation.DialogueShown dialogue) {
        String question = "The NPC says:\n\n" + dialogue.text().strip()
                + "\n\nWhat do you answer?";
        String answer = oracle.ask(SYSTEM, question);
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        return parse(answer);
    }

    /**
     * Pulls the decision out of whatever the model said.
     *
     * Scans for the keyword rather than requiring the whole reply to be one, because a local
     * reasoning model will explain itself however firmly it is told not to, and throwing away
     * a correct decision over a preamble helps nobody.
     */
    static Optional<Reply> parse(String answer) {
        String said = answer.toUpperCase();
        int choose = said.indexOf("CHOOSE");
        if (choose >= 0) {
            String rest = said.substring(choose + "CHOOSE".length()).trim();
            StringBuilder digits = new StringBuilder();
            for (char c : rest.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else if (!digits.isEmpty()) {
                    break;
                }
            }
            if (!digits.isEmpty()) {
                return Optional.of(new Reply((byte) 1, Integer.parseInt(digits.toString()),
                        "chose option " + digits));
            }
        }
        if (said.contains("DECLINE")) {
            return Optional.of(new Reply((byte) 0, Reply.NO_SELECTION, "declined"));
        }
        if (said.contains("CONTINUE")) {
            return Optional.of(new Reply((byte) 1, Reply.NO_SELECTION, "continued"));
        }
        log.debug("Could not read a decision out of: {}", answer);
        return Optional.empty();
    }
}
