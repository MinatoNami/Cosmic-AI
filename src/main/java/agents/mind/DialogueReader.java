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
              CHOOSE <n> - pick an option from a list, where n is the number in its #Ln# tag

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
            somewhere with no way back. You are told how you are placed before the NPC's \
            words - weigh the offer against that. Somewhere to go and things unfinished \
            mean an offer of passage is a distraction; nothing left within walking distance \
            means it is the only way on.

            An offer to take you on as one of them - to become something, to learn a way of \
            fighting or living - is different. It is how a character grows, and it is meant \
            to be final. If you have not yet taken up any calling and you meet what it asks, \
            CONTINUE through it when it suits your temperament, or when it is the first one \
            you have been offered in a long while. If you have one already, DECLINE.

            Something you were sent to do - a test, an errand - is finished only when it is. \
            If a list offers a way to leave it before you have what you were asked for, do not \
            choose it: DECLINE to end the conversation and carry on. When you are asked to \
            choose a path, choose the option that lets you decide, then the path that best \
            suits your temperament, and confirm it.

            Whenever the NPC's words contain a list of #L options, answer CHOOSE <n> or DECLINE. \
            CONTINUE is not an answer to a list.""";

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
    /**
     * @param situation how the agent is placed, in its own terms
     *
     * Without it the model answered every offer of passage with DECLINE, and was right to:
     * the prompt tells it to accept only if going there is what the agent wanted, and
     * nothing told it what the agent wanted. Shanks asks "do you want to go to Victoria
     * Island? It costs 150 mesos" - an excellent offer to somebody who has opened every door
     * on the island, and a distraction to somebody who has not.
     */
    public Optional<Reply> read(Observation.DialogueShown dialogue, String situation) {
        String question = "How you are placed: " + situation
                + "\n\nThe NPC says:\n\n" + dialogue.text().strip()
                + "\n\nWhat do you answer?";
        String answer = oracle.ask(SYSTEM, question);
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        return parse(answer).map(reply -> choosingFromAList(reply, dialogue.text()));
    }

    /** "#L3#I'll choose my occupation!" - a numbered option and its words. */
    private static final java.util.regex.Pattern OPTION =
            java.util.regex.Pattern.compile("#L(\\d+)#([^#\\r\\n]*)");

    /**
     * A list wants an option, and "carry on" is not one.
     *
     * Asked to pick from "#L0#Fighter #L1#Page #L2#Spearman", the model answered CONTINUE about
     * half the time, which sends no choice at all and leaves the NPC waiting on one. When that
     * happens the choice is made here: the option that says it is the choosing, or else the
     * first one offered. A DECLINE is left alone - declining is how a list is walked away from.
     */
    static Reply choosingFromAList(Reply reply, String said) {
        if (reply.action() != 1) {
            return reply;
        }
        java.util.Map<Integer, String> options = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher option = OPTION.matcher(said);
        while (option.find()) {
            options.put(Integer.parseInt(option.group(1)), option.group(2).toLowerCase());
        }
        if (options.isEmpty()) {
            return reply;
        }
        Integer choosing = options.entrySet().stream()
                .filter(o -> o.getValue().contains("choose") || o.getValue().contains("decide")
                        || o.getValue().contains("ready"))
                .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        String picked = options.get(reply.selection());
        // Asking to have a choice explained brings the same list straight back, so a reader
        // that keeps asking never chooses: the trainer's menu was answered "explain the
        // Fighter" every time. When there is an option that makes the choice, it is taken.
        if (picked != null && choosing != null && picked.contains("explain")) {
            return new Reply((byte) 1, choosing, "chose option " + choosing + " (the choosing, not another explanation)",
                    reply.needs());
        }
        if (reply.selection() != Reply.NO_SELECTION) {
            return reply;
        }
        if (choosing != null) {
            return new Reply((byte) 1, choosing, "chose option " + choosing + " (the choosing)", reply.needs());
        }
        // Never pick a way out on the reader's behalf. The colleague inside a second-job test
        // offers exactly one option - "I would like to leave" - and taking the first offered
        // would have walked the agent out of its own test.
        Integer first = options.entrySet().stream()
                .filter(o -> !(o.getValue().contains("leave") || o.getValue().contains("quit")
                        || o.getValue().contains("exit") || o.getValue().contains("give up")))
                .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        if (first == null) {
            return new Reply((byte) 0, Reply.NO_SELECTION, "declined a list whose only way on was out",
                    reply.needs());
        }
        return new Reply((byte) 1, first, "chose option " + first + " (the first offered)", reply.needs());
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
