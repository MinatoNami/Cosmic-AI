package agents.mind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a finished conversation for what the NPC wants done, and turns it into steps the agent
 * knows how to take.
 *
 * <p>{@link DialogueReader} answers one line at a time - carry on, decline, pick an option -
 * which is all a dialogue window asks. What it never did was step back once the window closed
 * and ask what the conversation as a whole wanted. Roger gives every new character an apple,
 * says "double click to consume" and "talk to me again when you recover your HP", and three
 * agents in a row walked off with the apple in their bags. No rule said "eat what Roger
 * gives you", and none should: the next NPC will want something else. So the model reads the
 * whole conversation, with what the agent was handed during it, and answers in a small fixed
 * vocabulary of things the agent can actually do.
 *
 * <p>Everything named in an answer is checked against what the agent can see: an item to use
 * must be in its bags, and a person or place must have been named in the conversation. A model
 * that invents an id gets that step dropped rather than an agent sent looking for nothing.
 */
public class InstructionReader {
    private static final Logger log = LoggerFactory.getLogger(InstructionReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The things an agent can be asked to do and knows how to. */
    public enum Kind {
        /** Use something it is carrying, {@code count} times. */
        USE_ITEM,
        /** Go and find somebody. */
        TALK_TO,
        /** Go somewhere. */
        GO_TO_MAP,
        /** Come back with {@code count} of something. */
        BRING_ITEM,
        /**
         * Head for somewhere named only in words - "Victoria Island", "Perion" - which the
         * agent has no id for. It cannot walk there by name, but it can remember being told,
         * and weigh the next offer of passage against it.
         */
        HEAD_FOR
    }

    public record Step(Kind kind, int id, int count, String place) {

        public Step(Kind kind, int id, int count) {
            this(kind, id, count, null);
        }

        public String describe() {
            return switch (kind) {
                case HEAD_FOR -> "head for " + place;
                case USE_ITEM -> "use item:" + id + (count > 1 ? " x" + count : "");
                case TALK_TO -> "talk to npc:" + id;
                case GO_TO_MAP -> "go to map:" + id;
                case BRING_ITEM -> "bring " + count + " item:" + id;
            };
        }
    }

    /**
     * One conversation, and what the agent knew at the end of it.
     *
     * @param received   what arrived in its bags while the NPC was talking, item id to count
     * @param carried    usable things in its bags, item id to count
     * @param healing    which of those it believes restore health
     * @param questsStarted quests that began during the conversation
     */
    public record Conversation(int npcId, List<String> lines, Map<Integer, Integer> received,
                               Map<Integer, Integer> carried, Set<Integer> healing,
                               List<Integer> questsStarted, int hp, int maxHp) {
    }

    static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["steps"],
             "properties":{"steps":{"type":"array","maxItems":4,"items":{
               "type":"object","additionalProperties":false,"required":["do","id","count"],
               "properties":{
                 "do":{"type":"string","enum":["USE_ITEM","TALK_TO","GO_TO_MAP","BRING_ITEM","HEAD_FOR"]},
                 "id":{"type":"integer"},
                 "count":{"type":"integer","minimum":1,"maximum":200},
                 "place":{"type":"string","maxLength":60}}}}}}""";

    private static final String SYSTEM = """
            You are playing a character in a 2D fantasy MMO. An NPC has just finished talking \
            to you. Work out what, if anything, they want you to do now, and turn it into steps \
            you can take.

            You can only do these things:
              USE_ITEM   id=<item id you are carrying>  count=<how many times>
              TALK_TO    id=<npc id named in the conversation>
              GO_TO_MAP  id=<map id named in the conversation>
              BRING_ITEM id=<item id>  count=<how many>
              HEAD_FOR   place=<a place named only in words, as the NPC said it>  id=0

            Use HEAD_FOR only when the NPC tells you to go somewhere in particular and gives \
            no #m id for it - "head over to Victoria Island and see the trainer in Perion".

            In the NPC's words, #p123# is a person, #m123# a place and #t123# an item, by id. \
            Things are also named in plain words; match those to what you were just given or \
            are carrying. An item handed to you in this conversation is usually the one the \
            NPC is talking about.

            Only list steps the NPC actually asked for, in the order to do them. Going back to \
            the NPC afterwards happens on its own; do not list it. If nothing was asked, \
            answer with no steps. Answer only with JSON: {"steps":[{"do":..,"id":..,"count":..,"place":..}]}""";

    private final Oracle oracle;

    public InstructionReader(Oracle oracle) {
        this.oracle = oracle;
    }

    /** The steps asked for, possibly none; an unreachable model means none. */
    public List<Step> read(Conversation conversation) {
        String answer = oracle.ask(SYSTEM, describe(conversation), SCHEMA);
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<Step> steps = checked(parse(answer), conversation);
        log.debug("npc:{} asked for {}", conversation.npcId(), steps);
        return steps;
    }

    static String describe(Conversation c) {
        StringBuilder text = new StringBuilder();
        text.append("You have ").append(c.hp()).append(" of ").append(c.maxHp()).append(" HP.\n");
        if (!c.questsStarted().isEmpty()) {
            text.append("During this conversation you took on quest ").append(c.questsStarted()).append(".\n");
        }
        if (c.received().isEmpty()) {
            text.append("You were given nothing.\n");
        } else {
            text.append("You were given, during this conversation:");
            c.received().forEach((item, count) -> text.append(" item:").append(item).append(" x").append(count)
                    .append(c.healing().contains(item) ? " (restores HP)" : ""));
            text.append(".\n");
        }
        if (!c.carried().isEmpty()) {
            text.append("Usable things you are carrying:");
            c.carried().forEach((item, count) -> text.append(" item:").append(item).append(" x").append(count)
                    .append(c.healing().contains(item) ? " (restores HP)" : ""));
            text.append(".\n");
        }
        text.append("\nnpc:").append(c.npcId()).append(" said:\n");
        for (String line : c.lines()) {
            text.append("- ").append(line.strip().replace("\r\n", " ").replace('\n', ' ')).append('\n');
        }
        text.append("\nWhat do they want you to do?");
        return text.toString();
    }

    static List<Step> parse(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        List<Step> steps = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(answer.substring(start, end + 1));
            for (JsonNode step : root.path("steps")) {
                Kind kind;
                try {
                    kind = Kind.valueOf(step.path("do").asText("").trim().toUpperCase());
                } catch (IllegalArgumentException unknown) {
                    continue;
                }
                int id = step.path("id").asInt(0);
                int count = Math.max(1, Math.min(200, step.path("count").asInt(1)));
                String place = step.path("place").asText("").strip();
                if (kind == Kind.HEAD_FOR) {
                    if (!place.isEmpty()) {
                        steps.add(new Step(kind, 0, 1, place.length() > 60 ? place.substring(0, 60) : place));
                    }
                } else if (id > 0) {
                    steps.add(new Step(kind, id, count));
                }
            }
        } catch (Exception unreadable) {
            log.debug("Could not read the steps in {}", answer);
        }
        return steps;
    }

    private static final Pattern NAMED = Pattern.compile("#([pmt])(\\d+)#");

    /** Whether the place was actually mentioned, so a model cannot send the agent somewhere imagined. */
    static boolean saidInWords(String place, List<String> lines) {
        String said = String.join(" ", lines).replaceAll("#[a-z]", "").toLowerCase();
        for (String word : place.toLowerCase().split("[^a-z]+")) {
            if (word.length() >= 4 && said.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps only steps about things the agent can see: an item it is carrying, or a person or
     * place the conversation named.
     */
    static List<Step> checked(List<Step> steps, Conversation c) {
        Set<Integer> people = new java.util.HashSet<>();
        Set<Integer> places = new java.util.HashSet<>();
        for (String line : c.lines()) {
            Matcher named = NAMED.matcher(line);
            while (named.find()) {
                int id = Integer.parseInt(named.group(2));
                if (named.group(1).equals("p")) {
                    people.add(id);
                } else if (named.group(1).equals("m")) {
                    places.add(id);
                }
            }
        }
        List<Step> kept = new ArrayList<>();
        for (Step step : steps) {
            boolean real = switch (step.kind()) {
                case USE_ITEM -> c.carried().containsKey(step.id()) || c.received().containsKey(step.id());
                case TALK_TO -> people.contains(step.id()) && step.id() != c.npcId();
                case GO_TO_MAP -> places.contains(step.id());
                case BRING_ITEM -> true;    // something to find, so not something it has yet
                case HEAD_FOR -> saidInWords(step.place(), c.lines());
            };
            if (real) {
                kept.add(step);
            } else {
                log.debug("Dropped {}: nothing by that id in sight", step.describe());
            }
        }
        return kept;
    }
}
