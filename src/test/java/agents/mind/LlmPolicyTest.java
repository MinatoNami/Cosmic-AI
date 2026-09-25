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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(decision.decidedBy().startsWith("reflex:"), decision.decidedBy());
        assertEquals("target gone", decision.fellBackBecause());
    }

    /**
     * The measurement the rest of this is for. A fallback is indistinguishable from a
     * deliberation once it reaches the world - same intent, same trace shape - so unless the
     * decision says who made it, an agent whose every answer was dropped reads exactly like
     * one the model is steering.
     */
    @Test
    void creditsTheModelWithWhatItActuallyDecided() {
        world.update(new Observation.MonsterAppeared(2, 9001, 100100, new Point(30, 0)));
        LlmPolicy policy = policyReturning("GOAL: try hitting it\nINTENT: Attack 9001");

        Policy.Decision decision = deliberate(policy, 2);

        assertEquals("llm:stub", decision.decidedBy());
        assertNull(decision.fellBackBecause(), "nothing fell back, so there is nothing to explain");
    }

    @Test
    void saysWhenTheModelAnsweredWithNothing() {
        LlmPolicy policy = new LlmPolicy(new Oracle() {
            public String ask(String system, String user) {
                return null;
            }

            public String name() {
                return "silent";
            }
        }, reflex, 1);

        assertEquals("model returned nothing", deliberate(policy, 1).fellBackBecause());
    }

    /**
     * Most fallbacks are not failures: reflexes fill the gaps between asks by design, and a
     * count that lumped those in with dropped answers would be useless for finding either.
     */
    @Test
    void separatesTheGapsBetweenAsksFromDroppedAnswers() {
        LlmPolicy policy = new LlmPolicy(new StubOracle("GOAL: x\nINTENT: Wait"), reflex, 4);
        assertEquals("llm:stub", deliberate(policy, 1).decidedBy());

        Policy.Decision next = policy.decide(mind, world, 2);

        assertEquals("between asks", next.fellBackBecause());
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

    /**
     * Three agents starting together used to ask on the same decision, so one laptop model
     * got three prompts in the same instant and all three ran out of token budget together.
     */
    @Test
    void agentsGivenDifferentPhasesDoNotAskOnTheSameDecision() {
        CountingOracle first = new CountingOracle();
        CountingOracle second = new CountingOracle();
        Policy a = new LlmPolicy(first, new ReflexPolicy(new Random(1)), 4, 0);
        Policy b = new LlmPolicy(second, new ReflexPolicy(new Random(1)), 4, 2);

        List<Integer> whenFirstAsked = new ArrayList<>();
        List<Integer> whenSecondAsked = new ArrayList<>();
        for (int decision = 0; decision < 8; decision++) {
            if (asked(a, first, decision)) {
                whenFirstAsked.add(decision);
            }
            if (asked(b, second, decision)) {
                whenSecondAsked.add(decision);
            }
        }

        assertFalse(whenFirstAsked.isEmpty(), "the unphased policy never asked at all");
        assertFalse(whenSecondAsked.isEmpty(), "the phased policy never asked at all");
        assertNotEquals(whenFirstAsked, whenSecondAsked,
                "both asked on the same decisions: " + whenFirstAsked);
    }

    /**
     * Makes one decision and says whether it asked the model.
     *
     * The policy hands the question to a background thread, so the count rises shortly after
     * decide() returns rather than during it - waiting briefly is the difference between
     * testing the behaviour and testing which thread won.
     */
    private boolean asked(Policy policy, CountingOracle oracle, int decision) {
        int before = oracle.asks;
        policy.decide(mind, world, decision);
        for (int waited = 0; waited < 100 && oracle.asks == before; waited++) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return oracle.asks > before;
    }

    /** Counts calls, so a test can see which decision triggered one. */
    private static class CountingOracle implements Oracle {
        private int asks;

        @Override
        public String ask(String system, String user) {
            asks++;
            return null;
        }

        @Override
        public String name() {
            return "counting";
        }
    }

    /**
     * The half of an answer that outlives the moment.
     *
     * A model answering once every thirty decisions cannot usefully choose an action - by the
     * time it replies the monster is dead - so what it says to pursue leans on the reflexes
     * until the next answer. Two earlier attempts at this failed because the intent lasted a
     * single decision, which is not long enough to reach anything.
     */
    @Test
    void anIntentionOutlivesTheDecisionThatSetIt() {
        ReflexPolicy reflexes = new ReflexPolicy(new Random(1), Disposition.FIGHTER);
        LlmPolicy policy = new LlmPolicy(
                new FixedOracle("GOAL: look around\nPURSUE: exploring\nINTENT: Wait"),
                reflexes, 8);

        // The question goes to a background thread, so the answer lands on some later
        // decision rather than the next one. Waiting for it is the difference between testing
        // the behaviour and testing which thread won - a mistake already made once in this
        // file. Far fewer decisions than the sixteen the intention lasts, either way.
        for (int decision = 0; decision < 12 && !reflexes.isUrged("door"); decision++) {
            policy.decide(mind, world, decision);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(reflexes.isUrged("door"),
                "the model asked it to explore and nothing carried that past the decision");
    }

    /**
     * The reflexes spend most of their effort finding somebody to talk to, and the model was
     * never told anybody was there - it could lean towards talking but not know whether that
     * made sense.
     */
    @Test
    void tellsTheModelWhoIsStandingAboutAndWhetherItHasMetThem() {
        world.update(new Observation.NpcAppeared(2, 700, 2100, new Point(50, 0)));
        StubOracle oracle = new StubOracle("GOAL: x\nPURSUE: talking");
        deliberate(new LlmPolicy(oracle, reflex, 1), 2);

        String prompt = oracle.prompts.get(0);
        assertTrue(prompt.contains("npc:2100 objectId 700"), prompt);
        assertTrue(prompt.contains("never spoken to it"), prompt);
    }

    @Test
    void walksUpToAnNpcTheModelNamed() {
        world.update(new Observation.NpcAppeared(2, 700, 2100, new Point(50, 0)));
        LlmPolicy policy = policyReturning("GOAL: meet them\nINTENT: TalkTo 700");

        Intent.TalkTo talk = assertInstanceOf(Intent.TalkTo.class, deliberate(policy, 2).intent());

        assertEquals(2100, talk.npcId());
        assertEquals(new Point(50, 0), talk.position());
    }

    /** What a 4B model actually writes, which is rarely what it was asked to. */
    @Test
    void readsAnActionThroughTheDecorationASmallModelPutsOnIt() {
        assertEquals(new Point(120, -40), assertInstanceOf(Intent.MoveTo.class,
                deliberate(policyReturning("INTENT: `moveTo (120, -40)`"), 1).intent()).destination());
        assertEquals(new Point(7, 8), assertInstanceOf(Intent.MoveTo.class,
                deliberate(policyReturning("INTENT: MoveTo(7,8)"), 2).intent()).destination());

        world.update(new Observation.MonsterAppeared(3, 9001, 100100, new Point(30, 0)));
        assertEquals(9001, assertInstanceOf(Intent.Attack.class,
                deliberate(policyReturning("INTENT: attack objectId 9001"), 3).intent()).objectId());
    }

    /**
     * Most answers arrive after the thing they were about has gone, but the lean in them
     * still steers the reflexes - and the trace has to say so, or a run the model was
     * steering reads as one it had no part in.
     */
    @Test
    void saysTheModelIsSteeringEvenWhenItsActionIsDropped() {
        LlmPolicy policy = policyReturning("GOAL: find people\nPURSUE: talking\nINTENT: Attack 4242");

        assertEquals("target gone; model leaning talk", deliberate(policy, 1).fellBackBecause());
    }

    @Test
    void aLeanWithNoActionIsAnAnswerNotAFailure() {
        LlmPolicy policy = policyReturning("GOAL: look around\nPURSUE: exploring\nINTENT: none");

        assertEquals("no action given; model leaning explore", deliberate(policy, 1).fellBackBecause());
    }

    /** Answers whatever it was built with, immediately. */
    private record FixedOracle(String answer) implements Oracle {
        @Override
        public String ask(String system, String user) {
            return answer;
        }

        @Override
        public String name() {
            return "fixed";
        }
    }
}
