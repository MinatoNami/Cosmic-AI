package agents.mind;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the policy through a stub oracle, so none of this needs a key or a network.
 *
 * What is worth pinning here is the failure behaviour. A model that returns nonsense, or
 * nothing, or an action aimed at something that is not there, must never take an agent off
 * the network - it should quietly fall back and carry on.
 */
class LlmPolicyTest {

    @TempDir
    Path traceDir;

    private Mind mind;
    private WorldModel world;
    private Policy reflex;

    /** Returns whatever it was given, and records what it was asked. */
    private static class StubOracle implements Oracle {
        private final String reply;
        final List<String> prompts = new ArrayList<>();

        StubOracle(String reply) {
            this.reply = reply;
        }

        @Override
        public String ask(String system, String user) {
            prompts.add(system + "\n" + user);
            return reply;
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    @BeforeEach
    void setUp() {
        mind = new Mind("Test", Trace.toFile(traceDir.resolve("t.jsonl"), "Test"));
        world = new WorldModel();
        world.update(new Observation.MapEntered(1, 10000, 0));
        world.movedTo(new Point(0, 0));
        reflex = new ReflexPolicy(new Random(1));
    }

    private LlmPolicy policyReturning(String reply) {
        return new LlmPolicy(new StubOracle(reply), reflex, 1);
    }

    /**
     * Deliberation happens in the background, so the first call only asks and the answer is
     * used on a later one. Tests drive both halves rather than pretending it is synchronous.
     */
    private Policy.Decision deliberate(LlmPolicy policy, long tick) {
        policy.decide(mind, world, tick);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (policy.isThinking() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return policy.decide(mind, world, tick);
    }

    @Test
    void followsTheActionTheModelChose() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(30, 0)));
        LlmPolicy policy = policyReturning("GOAL: try hitting it\nINTENT: Attack 9001");

        Policy.Decision decision = deliberate(policy, 2);

        assertEquals(9001, assertInstanceOf(Intent.Attack.class, decision.intent()).objectId());
        assertEquals("try hitting it", decision.goal());
    }

    /** The model names a target; where it is comes from what the agent can actually see. */
    @Test
    void fillsInThePositionFromTheWorld() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(140, 60)));
        LlmPolicy policy = policyReturning("GOAL: go\nINTENT: Attack 9001");

        Intent.Attack attack = assertInstanceOf(Intent.Attack.class,
                deliberate(policy, 2).intent());

        assertEquals(new Point(140, 60), attack.position());
    }

    @Test
    void fallsBackWhenAskedToActOnSomethingItCannotSee() {
        LlmPolicy policy = policyReturning("GOAL: swing wildly\nINTENT: Attack 4242");

        Policy.Decision decision = deliberate(policy, 1);

        assertFalse(decision.intent() instanceof Intent.Attack,
                "an object the agent cannot see is not a target");
    }

    @Test
    void fallsBackOnNonsense() {
        LlmPolicy policy = policyReturning("I think I would like to go for a walk, actually.");

        assertInstanceOf(Intent.MoveTo.class, deliberate(policy, 1).intent());
    }

    @Test
    void fallsBackWhenTheModelCannotBeReached() {
        LlmPolicy policy = new LlmPolicy(new Oracle() {
            public String ask(String system, String user) {
                return null;
            }

            public String name() {
                return "unreachable";
            }
        }, reflex, 1);

        assertInstanceOf(Intent.MoveTo.class, deliberate(policy, 1).intent());
    }

    @Test
    void recordsWhatTheModelWorkedOutAsInferred() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(30, 0)));
        mind.take(new Observation.MonsterAppeared(2, 9001, 100100, new Point(30, 0)));
        LlmPolicy policy = policyReturning("""
                GOAL: fight
                INTENT: Attack 9001
                LEARNED: monster:100100 | hurts | me
                """);

        deliberate(policy, 2);

        Belief learned = mind.semantic().liveBeliefs().stream()
                .filter(b -> b.predicate().equals("hurts"))
                .findFirst().orElseThrow();
        assertEquals(Belief.Provenance.INFERRED, learned.provenance());
        assertFalse(learned.supportedBy().isEmpty(), "even a conclusion names where it was drawn");
    }

    /**
     * The prompt is the one place the zero-knowledge boundary could quietly leak, so it gets
     * a test: ids go to the model, names never do.
     */
    @Test
    void tellsTheModelIdsAndNeverNames() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(30, 0)));
        StubOracle oracle = new StubOracle("GOAL: x\nINTENT: Wait");
        deliberate(new LlmPolicy(oracle, reflex, 1), 2);

        String prompt = oracle.prompts.get(0);
        assertTrue(prompt.contains("monster:100100"), "the model should be told the id");
        assertTrue(prompt.contains("map:10000"));
        assertFalse(prompt.toLowerCase().contains("snail"), "names would give the game away");
        assertFalse(prompt.toLowerCase().contains("mushroom town"));
    }

    /** Asking on every tick would be slow and expensive; reflexes fill the gaps. */
    @Test
    void onlyConsultsTheModelOccasionally() {
        StubOracle oracle = new StubOracle("GOAL: x\nINTENT: Wait");
        LlmPolicy policy = new LlmPolicy(oracle, reflex, 4);

        for (int i = 0; i < 8; i++) {
            policy.decide(mind, world, i);
            while (policy.isThinking()) {
                Thread.onSpinWait();
            }
        }

        assertEquals(2, oracle.prompts.size());
    }

    /**
     * The constraint that shaped this: a local model takes about fifteen seconds, and an
     * agent that waited would stop perceiving and be dropped by the server. Asking must
     * return immediately with a reflex, and the answer is used on a later tick.
     */
    @Test
    void neverWaitsForTheModel() {
        Oracle slow = new Oracle() {
            public String ask(String system, String user) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "GOAL: x\nINTENT: Wait";
            }

            public String name() {
                return "slow";
            }
        };

        long start = System.nanoTime();
        Policy.Decision decision = new LlmPolicy(slow, reflex, 1).decide(mind, world, 1);
        long tookMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(tookMillis < 1_000, "deciding took " + tookMillis + "ms; it must not block");
        assertInstanceOf(Intent.MoveTo.class, decision.intent());
    }
}
