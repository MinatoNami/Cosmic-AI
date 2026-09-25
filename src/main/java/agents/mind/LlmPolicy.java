package agents.mind;

import agents.Mind;
import agents.memory.Belief;
import agents.world.WorldModel;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decides by asking a model, and falls back to reflexes when it cannot.
 *
 * Two things about the prompt matter more than its wording.
 *
 * <strong>It contains ids, never names.</strong> The model is told about {@code monster:100100}
 * and {@code map:40000}, not "Blue Snail" and "Amherst". A model that has read the internet
 * knows what a blue snail is, and handing it the name would let it skip the exact work the
 * project exists to watch an agent do. Labels exist for the person reading the replay
 * ({@link agents.trace.Labels}); nothing in this path touches them.
 *
 * <strong>It contains the agent's memory, not the world.</strong> Everything in the prompt
 * came through an observation and is in the belief graph with an episode behind it. The
 * model cannot see the map, the server, or anything the agent has not perceived.
 *
 * <strong>Asking happens in the background.</strong> A local 35B model takes around fifteen
 * seconds to answer, and an agent that blocked for that long would stop perceiving, stop
 * acknowledging the server and eventually be disconnected. So a question goes out, reflexes
 * carry the agent meanwhile, and the answer is used when it arrives - which is a fair model
 * of deliberation anyway. The reply is parsed at the moment it is used rather than when it
 * arrives, so an answer that names something since killed or picked up simply fails to
 * resolve and is dropped.
 */
public class LlmPolicy implements Policy {

    /**
     * How often to actually ask. Reflexes fill the gaps, which keeps a population of agents
     * affordable and stops the model being asked to re-decide a walk it is halfway through.
     */
    private static final int DEFAULT_DELIBERATE_EVERY = 8;

    /**
     * Kept small deliberately. A reasoning model's thinking grows with the context it is
     * given, and a local one has a fixed token budget to spend: at twenty-five beliefs this
     * model used its entire budget reasoning and returned no answer at all. Eight is enough
     * to decide with and cheap enough to answer from.
     */
    private static final int BELIEFS_IN_PROMPT = 8;

    /** The same reasoning, for the people standing about. */
    private static final int NPCS_IN_PROMPT = 4;

    private static final String SYSTEM = """
            You are playing a character in an online game world you have never seen before.
            You know nothing about it beyond what you have observed, and the observations are
            given to you as opaque ids: a monster is a number, a map is a number, an NPC is a
            number. Nobody will tell you what any of them mean. Working that out by acting and
            watching what happens is the whole of your task.

            You will be given what you currently believe and what you can see right now.
            You think slowly: by the time your answer arrives, what you saw may have moved,
            died or been picked up. So the most useful thing you can give is a direction, not
            a single move.

            Reply in exactly this form, one item per line, and nothing else:

            GOAL: <a few words on what you are trying to achieve>
            PURSUE: <one of: fighting, looting, talking, exploring, errands>
            INTENT: <one of the actions below>
            LEARNED: <subject> | <predicate> | <object>

            PURSUE matters most. It is what you want to keep doing for the next half a minute
            or so. Reflexes carry it out between your answers; you are saying which way to
            lean, not giving an order. Talking to somebody you have never spoken to is often
            how you find out what a place is for.

            INTENT is one action to take right now. Leave it out if nothing in front of you
            needs doing this moment.

            LEARNED lines are optional and may repeat. Use them only for something you have
            worked out that is not already in your beliefs, phrased with the same kind of ids
            you were given. Do not restate what you were told.

            Actions, one per INTENT line, with no brackets or punctuation around the numbers:
              MoveTo <x> <y>
              Attack <objectId>
              PickUp <objectId>
              TalkTo <objectId>
              Say <message>
              EnterPortal <portalName>
              Wait
            """;

    private final Oracle oracle;
    private final Policy fallback;
    private final int deliberateEvery;
    private final ExecutorService thinking = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "deliberation");
        thread.setDaemon(true);      // never hold a run open waiting on a thought
        return thread;
    });

    private CompletableFuture<String> pending;
    private String unreadReply;
    private int decisions;

    /**
     * Why the last answer was no use, waiting to be written to the trace.
     *
     * Read once and cleared: a failed answer explains exactly one fallback, and the decisions
     * after it are back to the ordinary wait for the next ask.
     */
    private String unusedAnswer;

    public LlmPolicy(Oracle oracle, Policy fallback) {
        this(oracle, fallback, DEFAULT_DELIBERATE_EVERY);
    }

    public LlmPolicy(Oracle oracle, Policy fallback, int deliberateEvery) {
        this(oracle, fallback, deliberateEvery, 0);
    }

    /**
     * @param phase where in the cycle this policy starts counting, so a population does not
     *              ask all at once. Every agent starts its first decision at the same moment
     *              and asks every {@code deliberateEvery} decisions after, which put three
     *              agents in lockstep: one local model receiving three prompts in the same
     *              instant, three times a minute, and idle in between. Spreading them costs
     *              nothing and is the difference between a queue and a stampede.
     */
    public LlmPolicy(Oracle oracle, Policy fallback, int deliberateEvery, int phase) {
        this.oracle = oracle;
        this.fallback = fallback;
        this.deliberateEvery = Math.max(1, deliberateEvery);
        this.decisions = Math.floorMod(phase, this.deliberateEvery);
    }

    @Override
    public Decision decide(Mind mind, WorldModel world, long tick) {
        collectAnswer();
        List<Belief> recalled = mind.recall(situationTopic(world), tick, BELIEFS_IN_PROMPT);

        if (pending == null && decisions++ % deliberateEvery == 0 && worthAsking()) {
            String question = describe(mind, world, recalled);
            pending = CompletableFuture.supplyAsync(() -> oracle.ask(SYSTEM, question), thinking);
        }

        if (unreadReply == null) {
            return fellBack(mind, world, tick, whyNotTheModel());
        }

        Reply parsed = Reply.parse(unreadReply, world);
        unreadReply = null;

        // The lasting half of the answer. A model asked once every thirty decisions cannot
        // usefully pick a single action - by the time it replies, the monster it was looking
        // at is dead - but what it wants the agent to be doing outlives the moment, so it
        // leans on the reflexes until the next answer arrives and they work out the how.
        parsed.pursue().ifPresent(kind -> {
            if (fallback instanceof ReflexPolicy reflexes) {
                reflexes.urge(kind, deliberateEvery * 2);
            }
        });

        // What it worked out is worth keeping whether or not its action still makes sense:
        // the conclusion was drawn from what it saw, not from where the monster is now.
        parsed.learned().forEach(triple ->
                mind.infer(triple.subject(), triple.predicate(), triple.object(), tick));

        Optional<Intent> intent = parsed.intent();
        if (intent.isEmpty()) {
            // The reflexes act, but a lean the model gave is still its doing, and a trace
            // that only said "target gone" would read as the model having no say at all.
            String because = parsed.pursue()
                    // The reflexes call exploring "door"; the trace is read by people.
                    .map(kind -> parsed.problem() + "; model leaning "
                            + (kind.equals("door") ? "explore" : kind))
                    .orElse(parsed.problem());
            return fellBack(mind, world, tick, because);
        }

        return new Decision(intent.get(),
                parsed.goal().orElse("(no goal given)"),
                recalled.stream().map(Belief::ref).toList(),
                List.of("MoveTo", "Attack", "PickUp", "TalkTo", "Say", "EnterPortal", "Wait"))
                .creditedTo(name(), null);
    }

    /**
     * Whether the model is worth asking for a whole decision just now.
     *
     * A model that answers nothing still costs everything. This one reasons its way to the
     * token limit on the deliberation prompt every single time, and LM Studio serves one
     * request at a time - so each failed deliberation held the model for the better part of
     * a minute while an NPC stood waiting, and the dialogue it was asked to read timed out
     * instead. Two conversations in ten minutes, one of them answered by the reflex with
     * "nobody read it in time", while the agent was standing in front of the person who
     * sells passage off the island.
     *
     * Reading an NPC is the more valuable of the two and the one this model is good at, so
     * deliberation gets out of its way after a run of failures and tries again later.
     */
    private boolean worthAsking() {
        if (failuresInARow < GIVE_IT_A_REST) {
            return true;
        }
        if (--restingFor > 0) {
            return false;
        }
        failuresInARow = 0;         // one more try, and back to resting if it fails again
        restingFor = REST_FOR;
        return true;
    }

    /** How many answerless replies before deliberation stops competing for the model. */
    private static final int GIVE_IT_A_REST = 3;

    /** And how many decisions it stays out of the way for. */
    private static final int REST_FOR = 200;

    private int failuresInARow;
    private int restingFor = REST_FOR;

    private Decision fellBack(Mind mind, WorldModel world, long tick, String because) {
        return fallback.decide(mind, world, tick).creditedTo(fallback.name(), because);
    }

    /**
     * Why this decision is not the model's.
     *
     * Most of the time the honest answer is that nobody was asked: reflexes fill the gaps
     * between asks by design, and counting those as failures would bury the ones that are.
     */
    private String whyNotTheModel() {
        if (unusedAnswer != null) {
            String because = unusedAnswer;
            unusedAnswer = null;
            return because;
        }
        return pending != null ? "still thinking" : "between asks";
    }

    /** Takes an answer if one has arrived, without ever waiting for one. */
    private void collectAnswer() {
        if (pending == null || !pending.isDone()) {
            return;
        }
        String reply = null;
        try {
            reply = pending.getNow(null);
        } catch (RuntimeException e) {
            // Both oracles answer an unreachable server with null rather than an exception,
            // so this is here for the next one. An oracle that throws should cost the agent a
            // decision, not the run.
            unusedAnswer = "asking failed";
        }
        pending = null;
        if (reply != null && !reply.isBlank()) {
            unreadReply = reply;
            failuresInARow = 0;
        } else if (unusedAnswer == null) {
            failuresInARow++;
            // Null covers a timeout, a refused connection and a reasoning model that spent its
            // whole budget thinking. Which one it was is in the log; that this decision was
            // not the model's belongs in the trace.
            unusedAnswer = "model returned nothing";
        }
    }

    /** Visible for tests: true while a question is outstanding. */
    boolean isThinking() {
        return pending != null && !pending.isDone();
    }

    /** What to search memory for - whatever is in front of the agent right now. */
    private static String situationTopic(WorldModel world) {
        StringBuilder topic = new StringBuilder("map:" + world.mapId());
        world.nearestMonster().ifPresent(m -> topic.append(" monster:").append(m.typeId()));
        world.nearestDrop().ifPresent(d -> topic.append(" item:").append(d.typeId()));
        return topic.toString();
    }

    private static String describe(Mind mind, WorldModel world, List<Belief> recalled) {
        StringBuilder out = new StringBuilder();

        out.append("You are ").append(mind.name())
                .append(", level ").append(world.level())
                .append(", hp ").append(world.hp()).append("/").append(world.maxHp())
                .append(", standing at ").append(point(world.selfPosition()))
                .append(" in map:").append(world.mapId()).append(".\n\n");

        out.append("What you can see:\n");
        world.nearestMonster().ifPresentOrElse(
                m -> out.append("  monster:").append(m.typeId())
                        .append(" objectId ").append(m.objectId())
                        .append(" at ").append(point(m.position())).append('\n'),
                () -> out.append("  no monsters\n"));
        world.nearestDrop().ifPresent(d -> out.append("  item:").append(d.typeId())
                .append(" objectId ").append(d.objectId())
                .append(" at ").append(point(d.position())).append('\n'));
        // Nearest first and only a few: a town can have a dozen, and each line is tokens a
        // small model spends reasoning about somebody it will not walk to anyway.
        Set<String> heard = ReflexPolicy.spokenTo(mind);
        Point self = world.selfPosition();
        world.visibleNpcs().stream()
                .sorted(Comparator.comparingDouble(n -> n.position().distance(self)))
                .limit(NPCS_IN_PROMPT)
                .forEach(n -> out.append("  npc:").append(n.typeId())
                        .append(" objectId ").append(n.objectId())
                        .append(" at ").append(point(n.position()))
                        .append(heard.contains("npc:" + n.typeId())
                                ? " - you have spoken to it\n"
                                : " - you have never spoken to it\n"));
        world.visiblePlayers().forEach((id, name) ->
                out.append("  another player, ").append(name).append(", id ").append(id).append('\n'));
        List<WorldModel.PortalTarget> portals = world.portals();
        for (WorldModel.PortalTarget portal : portals) {
            out.append("  a way out named ").append(portal.name())
                    .append(" at ").append(point(portal.position()))
                    .append(" - you do not know where it goes\n");
        }

        out.append("\nWhat you believe, most relevant first:\n");
        if (recalled.isEmpty()) {
            out.append("  nothing yet\n");
        }
        for (Belief belief : recalled) {
            out.append("  ").append(belief.asSentence())
                    .append("  (confidence ").append(String.format("%.2f", belief.confidence()))
                    .append(", ").append(belief.provenance().name().toLowerCase()).append(")\n");
        }
        return out.toString();
    }

    private static String point(Point p) {
        return "(" + p.x + ", " + p.y + ")";
    }

    @Override
    public java.util.Optional<Oracle> oracle() {
        return java.util.Optional.of(oracle);
    }

    @Override
    public String name() {
        return "llm:" + oracle.name();
    }

    /**
     * A parsed reply. Anything unrecognised is dropped rather than guessed at, and
     * {@code problem} says what was dropped, so a run of reflex decisions can be read back as
     * the model missing rather than the model agreeing.
     */
    record Reply(Optional<String> goal, Optional<Intent> intent, Optional<String> pursue,
                 List<Triple> learned, String problem) {

        record Triple(String subject, String predicate, String object) {
        }

        /** An action line, or the few words explaining why it never became one. */
        private record Resolved(Optional<Intent> intent, String problem) {
            static Resolved to(Intent intent) {
                return new Resolved(Optional.of(intent), null);
            }

            static Resolved not(String problem) {
                return new Resolved(Optional.empty(), problem);
            }
        }

        static Reply parse(String text, WorldModel world) {
            Optional<String> goal = Optional.empty();
            Optional<String> pursue = Optional.empty();
            Resolved resolved = Resolved.not("no action given");
            List<Triple> learned = new ArrayList<>();

            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("GOAL:")) {
                    goal = Optional.of(trimmed.substring(5).trim());
                } else if (trimmed.startsWith("INTENT:")) {
                    resolved = parseIntent(trimmed.substring(7).trim(), world);
                } else if (trimmed.startsWith("PURSUE:")) {
                    pursue = asKind(trimmed.substring(7).trim());
                } else if (trimmed.startsWith("LEARNED:")) {
                    parseTriple(trimmed.substring(8).trim()).ifPresent(learned::add);
                }
            }
            return new Reply(goal, resolved.intent(), pursue, learned, resolved.problem());
        }

        /**
         * Turns what the model called it into what the reflexes call it.
         *
         * Scanning for the word rather than demanding the line be exactly one, because a
         * local reasoning model will write "exploring, I think" however plainly it is asked
         * not to, and throwing away an intention over a trailing clause helps nobody.
         */
        private static Optional<String> asKind(String said) {
            String lower = said.toLowerCase();
            if (lower.contains("fight")) {
                return Optional.of("fight");
            }
            if (lower.contains("loot")) {
                return Optional.of("loot");
            }
            if (lower.contains("talk")) {
                return Optional.of("talk");
            }
            if (lower.contains("explor")) {
                return Optional.of("door");
            }
            if (lower.contains("errand") || lower.contains("quest")) {
                return Optional.of("errand");
            }
            return Optional.empty();
        }

        /** Any whole number, sign included, wherever it sits among brackets and commas. */
        private static final Pattern NUMBER = Pattern.compile("-?\\d+");

        /** The verb is the leading letters, so {@code MoveTo(120,-40)} splits as well as with a space. */
        private static final Pattern ACTION = Pattern.compile("([A-Za-z]*)(.*)", Pattern.DOTALL);

        /**
         * Reads an action line the way a small model writes one, not the way it was asked to.
         *
         * A 4B model asked for {@code MoveTo 120 -40} writes {@code `moveTo (120, -40)`} or
         * {@code Attack objectId 9001} often enough that strict parsing threw away a real
         * share of the few answers that arrived at all. The verb is matched without case or
         * decoration and numbers are picked out of whatever surrounds them; what it refers
         * to is still checked against the world, so leniency never invents a target.
         */
        private static Resolved parseIntent(String text, WorldModel world) {
            Matcher line = ACTION.matcher(text.replaceAll("[`*\"]", "").trim());
            line.matches();                 // cannot fail: both groups accept nothing
            String verb = line.group(1).toLowerCase();
            String rest = line.group(2).trim();
            List<Integer> numbers = numbersIn(rest);

            return switch (verb) {
                case "moveto" -> numbers.size() < 2 ? Resolved.not("MoveTo without a place")
                        : Resolved.to(new Intent.MoveTo(new Point(numbers.get(0), numbers.get(1))));
                // The model names what it wants to act on; where that thing is comes
                // from the world model. An id the agent cannot currently see is dropped
                // rather than acted on at a guessed position - and counted, because an
                // answer that takes fifteen seconds to arrive about a monster that lives
                // five is the failure most worth being able to measure.
                case "attack" -> firstNumber(numbers)
                        .flatMap(world::byObjectId)
                        .map(e -> Resolved.to(new Intent.Attack(e.objectId(), e.position())))
                        .orElseGet(() -> numbers.isEmpty() ? Resolved.not("not a number")
                                : Resolved.not("target gone"));
                case "pickup" -> firstNumber(numbers)
                        .flatMap(world::byObjectId)
                        .map(e -> Resolved.to(new Intent.PickUp(e.objectId(), e.position())))
                        .orElseGet(() -> numbers.isEmpty() ? Resolved.not("not a number")
                                : Resolved.not("target gone"));
                case "talkto" -> firstNumber(numbers)
                        .flatMap(id -> world.visibleNpcs().stream()
                                .filter(n -> n.objectId() == id).findFirst())
                        .map(n -> Resolved.to(new Intent.TalkTo(n.objectId(), n.typeId(), n.position())))
                        .orElseGet(() -> numbers.isEmpty() ? Resolved.not("not a number")
                                : Resolved.not("nobody there to talk to"));
                case "say" -> rest.isBlank() ? Resolved.not("nothing to say")
                        : Resolved.to(new Intent.Say(rest));
                case "enterportal" -> world.portalNamed(rest.replaceAll("[<>()\\[\\]]", "").trim())
                        .map(portal -> Resolved.to(new Intent.EnterPortal(portal.name(), portal.position())))
                        .orElseGet(() -> Resolved.not("no way out called that"));
                case "wait" -> Resolved.to(new Intent.Wait());
                // Told it may leave the line out, a model will as often write it empty.
                case "", "none", "nothing" -> Resolved.not("no action given");
                default -> Resolved.not("not an action");
            };
        }

        private static List<Integer> numbersIn(String text) {
            List<Integer> numbers = new ArrayList<>();
            Matcher matcher = NUMBER.matcher(text);
            while (matcher.find()) {
                try {
                    numbers.add(Integer.parseInt(matcher.group()));
                } catch (NumberFormatException e) {
                    // Digits too long for an int. Nothing real has an id like that.
                }
            }
            return numbers;
        }

        private static Optional<Integer> firstNumber(List<Integer> numbers) {
            return numbers.isEmpty() ? Optional.empty() : Optional.of(numbers.get(0));
        }

        private static Optional<Triple> parseTriple(String text) {
            String[] parts = text.split("\\|");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String subject = parts[0].trim();
            String predicate = parts[1].trim().replace(' ', '_');
            String object = parts[2].trim();
            if (subject.isBlank() || predicate.isBlank() || object.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Triple(subject, predicate, object));
        }
    }
}
