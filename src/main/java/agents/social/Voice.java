package agents.social;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Random;

/**
 * Keeps an agent from talking over itself.
 *
 * Without this an agent answers in the same 600ms step the question arrives in, and answers
 * every question in a step at once, so a busy map produces a wall of text from one character
 * in one instant. Nothing about that is wrong - it is just not what a person looks like, and
 * these are meant to be watched.
 *
 * Two rules, both on the wall clock rather than on ticks: ticks count packets received, so a
 * quiet map and a crowded one run at wildly different speeds and pacing measured in them
 * would be pacing measured in nothing.
 *
 * <ol>
 *   <li>A pause before answering, as though the reply were being typed.</li>
 *   <li>A minimum gap between any two things said, jittered so a population does not fall
 *       into step with itself.</li>
 * </ol>
 *
 * Queued rather than dropped: a question deferred is answered late, and a question dropped
 * looks like an agent ignoring you.
 */
public class Voice {

    /** Long enough to read as deliberate, short enough that a watcher does not give up. */
    public static final Duration DEFAULT_GAP = Duration.ofSeconds(4);

    /** Added to the gap at random, so agents that arrive together do not chorus. */
    public static final Duration DEFAULT_JITTER = Duration.ofSeconds(3);

    /** The beat before a reply, so an answer does not land in the same instant as the question. */
    public static final Duration DEFAULT_THINKING = Duration.ofMillis(1200);

    /**
     * A cap on what is waiting to be said. Reached only when someone is talking to an agent
     * faster than it is allowed to answer, and at that point the oldest question is the least
     * worth answering.
     */
    private static final int MOST_HELD = 8;

    private final Duration gap;
    private final Duration jitter;
    private final Duration thinking;
    private final Random random;
    private final Deque<Utterance> waiting = new ArrayDeque<>();

    private long nextAllowedAtMillis;

    public Voice(Random random) {
        this(DEFAULT_GAP, DEFAULT_JITTER, DEFAULT_THINKING, random);
    }

    public Voice(Duration gap, Duration jitter, Duration thinking, Random random) {
        this.gap = gap;
        this.jitter = jitter;
        this.thinking = thinking;
        this.random = random;
    }

    /**
     * Something an agent means to say.
     *
     * @param whisperTo the character to whisper to, or null to say it to the map
     */
    public record Utterance(String text, String whisperTo, long dueAtMillis) {
    }

    /**
     * Queues a reply, which waits a beat so it does not arrive in the same breath as the
     * question - but goes ahead of anything the agent was only announcing.
     *
     * Ordering matters more than it looks. An agent that shares a belief queues one message
     * plus a whisper to everyone it has met, so a plain queue can hold an answer behind half
     * a minute of unrelated chatter, and a question answered that late is indistinguishable
     * from a question ignored. Someone asked; that comes first.
     */
    public void reply(String text, String whisperTo, long nowMillis) {
        enqueue(new Utterance(text, whisperTo, nowMillis + thinking.toMillis()), true);
    }

    /** Queues something the agent wants to announce, which can go as soon as the gap allows. */
    public void announce(String text, long nowMillis) {
        enqueue(new Utterance(text, null, nowMillis), false);
    }

    /** Queues something said to one player by name, with no pause - nobody asked a question. */
    public void tell(String text, String whisperTo, long nowMillis) {
        enqueue(new Utterance(text, whisperTo, nowMillis), false);
    }

    /**
     * @param first true for something said in answer, which goes to the head of the queue and
     *              displaces the stalest announcement rather than itself when the queue is full
     */
    private void enqueue(Utterance utterance, boolean first) {
        while (waiting.size() >= MOST_HELD) {
            waiting.removeFirst();
        }
        if (first) {
            waiting.addFirst(utterance);
        } else {
            waiting.addLast(utterance);
        }
    }

    /**
     * The next thing to say, if anything is due and the agent has been quiet long enough.
     *
     * Taking one is what starts the next gap, so a caller that ignores the result silently
     * gags the agent - which is why this returns rather than sends.
     */
    public Optional<Utterance> next(long nowMillis) {
        if (waiting.isEmpty() || nowMillis < nextAllowedAtMillis) {
            return Optional.empty();
        }
        if (waiting.peekFirst().dueAtMillis() > nowMillis) {
            return Optional.empty();
        }
        Utterance speaking = waiting.removeFirst();
        nextAllowedAtMillis = nowMillis + gap.toMillis()
                + (jitter.isZero() ? 0 : random.nextLong(jitter.toMillis()));
        return Optional.of(speaking);
    }

    /** True when something is queued, said or not. */
    public boolean hasSomethingToSay() {
        return !waiting.isEmpty();
    }

    /** Milliseconds until the agent is allowed to speak again, 0 when it already is. */
    public long quietFor(long nowMillis) {
        return Math.max(0, nextAllowedAtMillis - nowMillis);
    }
}
