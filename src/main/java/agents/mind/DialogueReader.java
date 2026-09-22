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

            Answer with one decision line:
              CONTINUE   - agree, accept, or move the conversation on
              DECLINE    - refuse, or end the conversation
              CHOOSE <n> - pick option n, counting from 0, when a list is offered

            If this one told you something you must do or have before it will help - a level,
            a sum of money, an item, somewhere to be - add one more line:
              NEEDS: <what it said you need, in a few words>

            Only when a condition was actually stated. It is how you will remember to come
            back, so write what would let you recognise the moment you qualify.

            Prefer CONTINUE when the NPC is offering you something, work, or information. \
            Prefer DECLINE when it would cost you something you cannot judge, or when the \
            conversation is over.

            Take particular care with an offer to take you somewhere. Being moved is not \
            easily undone: it can skip everything you were in the middle of, or put you \
            somewhere with no way back. Say CONTINUE only if going there is what you \
            actually wanted.""";

    /**
     * What to send back: the action byte, a menu selection when one was asked for, and
     * anything the NPC said was required before it would help.
     *
     * @param needs what this one wants of the agent first, or null - an NPC that says "come
     *              back at level seven with 150 mesos" has given the agent a reason to
     *              return, and an agent that forgets the moment the window closes will only
     *              ever hear it again by accident
     */
    public record Reply(byte action, int selection, String why, String needs) {
        public static final int NO_SELECTION = -1;

        Reply(byte action, int selection, String why) {
            this(action, selection, why, null);
        }
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
        String found = null;
        for (String line : answer.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith("NEEDS:")) {
                String said = trimmed.substring(6).trim();
                if (!said.isBlank() && !said.equalsIgnoreCase("none")) {
                    found = said;
                }
            }
        }
        String needs = found;
        return decisionIn(answer).map(reply ->
                new Reply(reply.action(), reply.selection(), reply.why(), needs));
    }

    private static Optional<Reply> decisionIn(String answer) {
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
