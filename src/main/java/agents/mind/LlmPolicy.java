package agents.mind;

import agents.Mind;
import agents.memory.Belief;
import agents.world.WorldModel;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private static final String SYSTEM = """
            You are playing a character in an online game world you have never seen before.
            You know nothing about it beyond what you have observed, and the observations are
            given to you as opaque ids: a monster is a number, a map is a number, an NPC is a
            number. Nobody will tell you what any of them mean. Working that out by acting and
            watching what happens is the whole of your task.

            You will be given what you currently believe and what you can see right now.
            Choose one action.

            Reply in exactly this form, one item per line, and nothing else:

            GOAL: <a few words on what you are trying to achieve>
            INTENT: <one of the actions below>
            PURSUE: <one of: fighting, looting, talking, exploring, errands>
            LEARNED: <subject> | <predicate> | <object>

            PURSUE is what you want to keep doing for the next half a minute or so, not just
            now. Reflexes will carry it out between your answers; you are saying which way to
            lean, not giving an order.

            LEARNED lines are optional and may repeat. Use them only for something you have
            worked out that is not already in your beliefs, phrased with the same kind of ids
            you were given. Do not restate what you were told.

            Actions, one per INTENT line:
              MoveTo <x> <y>
              Attack <objectId>
              PickUp <objectId>
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

        Optional<Intent> intent = parsed.intent();
        if (intent.isEmpty()) {
            return fellBack(mind, world, tick, parsed.problem());
        }

        parsed.learned().forEach(triple ->
                mind.infer(triple.subject(), triple.predicate(), triple.object(), tick));

        return new Decision(intent.get(),
                parsed.goal().orElse("(no goal given)"),
                recalled.stream().map(Belief::ref).toList(),
                List.of("MoveTo", "Attack", "PickUp", "Say", "EnterPortal", "Wait"))
                .creditedTo(name(), null);
    }

    /** Reflexes decide this one, and the trace says so and why. */
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
            Resolved resolved = Resolved.not("no INTENT line");
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

        private static Resolved parseIntent(String text, WorldModel world) {
            String[] parts = text.split("\\s+", 2);
            String verb = parts[0];
            String rest = parts.length > 1 ? parts[1].trim() : "";

            try {
                return switch (verb) {
                    case "MoveTo" -> {
                        String[] xy = rest.split("\\s+");
                        yield xy.length < 2 ? Resolved.not("MoveTo without a place")
                                : Resolved.to(new Intent.MoveTo(
                                        new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]))));
                    }
                    // The model names what it wants to act on; where that thing is comes
                    // from the world model. An id the agent cannot currently see is dropped
                    // rather than acted on at a guessed position - and counted, because an
                    // answer that takes fifteen seconds to arrive about a monster that lives
                    // five is the failure most worth being able to measure.
                    case "Attack" -> world.byObjectId(Integer.parseInt(rest))
                            .map(e -> Resolved.to(new Intent.Attack(e.objectId(), e.position())))
                            .orElseGet(() -> Resolved.not("target gone"));
                    case "PickUp" -> world.byObjectId(Integer.parseInt(rest))
                            .map(e -> Resolved.to(new Intent.PickUp(e.objectId(), e.position())))
                            .orElseGet(() -> Resolved.not("target gone"));
                    case "Say" -> rest.isBlank() ? Resolved.not("nothing to say")
                            : Resolved.to(new Intent.Say(rest));
                    case "EnterPortal" -> world.portalNamed(rest)
                            .map(portal -> Resolved.to(new Intent.EnterPortal(portal.name(), portal.position())))
                            .orElseGet(() -> Resolved.not("no way out called that"));
                    case "Wait" -> Resolved.to(new Intent.Wait());
                    default -> Resolved.not("not an action");
                };
            } catch (NumberFormatException e) {
                return Resolved.not("not a number");
            }
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
