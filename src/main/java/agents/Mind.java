package agents;

import agents.memory.Belief;
import agents.memory.BeliefFormer;
import agents.memory.DecisionRecord;
import agents.memory.Episode;
import agents.memory.EpisodicMemory;
import agents.memory.Inferrer;
import agents.memory.MindSnapshot;
import agents.memory.Recall;
import agents.memory.SemanticMemory;
import agents.percept.Observation;
import agents.trace.Trace;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One agent's memory, and the path an observation takes through it.
 *
 * Perceive, record, conclude, trace - in that order, every time. Nothing reaches a belief
 * without passing through an episode first, so every conclusion has evidence behind it by
 * construction rather than by discipline.
 */
public class Mind implements AutoCloseable {
    private final String name;
    private final EpisodicMemory episodic = new EpisodicMemory();
    private final SemanticMemory semantic = new SemanticMemory();
    private final BeliefFormer former = new BeliefFormer();
    private final Inferrer inferrer = new Inferrer();
    private final Deque<DecisionRecord> decisions = new ArrayDeque<>();
    private final Trace trace;

    /**
     * How many decisions to keep to hand. Enough to answer "why did you do that" about
     * something a person just watched, not so many that an agent carries its whole history
     * in memory - the trace file has the rest.
     */
    private static final int DECISIONS_REMEMBERED = 20;

    public Mind(String name, Trace trace) {
        this.name = name;
        this.trace = trace;
    }

    /** Files an observation and records whatever it states outright. */
    public void take(Observation observation) {
        Episode episode = episodic.record(observation);
        trace.observed(episode);

        // Concluded rather than restated, and kept apart from restatement so a replay can
        // always tell which is which. These arrive as INFERRED, which the confidence curve
        // scores below anything actually seen.
        for (Inferrer.Conclusion conclusion : inferrer.consider(observation)) {
            infer(conclusion.subject(), conclusion.predicate(), conclusion.object(),
                    observation.tick());
        }

        for (BeliefFormer.Triple triple : former.beliefsFrom(episode)) {
            SemanticMemory.Assertion assertion = semantic.assertTriple(
                    triple.subject(), triple.predicate(), triple.object(),
                    episode.id(), episode.tick(), triple.provenance());

            trace.believed(assertion.belief(), !assertion.isNew());
            if (assertion.contradicted() != null) {
                // Re-read it: assertTriple stored the invalidated version in place.
                semantic.byId(assertion.contradicted().id())
                        .ifPresent(invalidated -> trace.revised(invalidated, assertion.belief()));
            }
        }
    }

    public void takeAll(List<Observation> observations) {
        observations.forEach(this::take);
    }

    /**
     * Writes a decision to the trace and keeps it to hand.
     *
     * @param by the policy that made this one, which the agent knows and the mind does not
     * @param fellBackBecause why the policy that was asked did not make it, or null
     * @return the action's ref, for anything that wants to point at it
     */
    public String decided(long tick, String goal, String intent, Map<String, Object> detail,
                          List<String> usedBeliefs, String by, String fellBackBecause) {
        String because = trace.deliberated(tick, goal, usedBeliefs, List.of(), by, fellBackBecause);
        String actionRef = trace.acted(tick, intent, detail, because);

        decisions.addLast(new DecisionRecord(tick, actionRef, intent, goal, usedBeliefs));
        while (decisions.size() > DECISIONS_REMEMBERED) {
            decisions.removeFirst();
        }
        return actionRef;
    }

    public Optional<DecisionRecord> lastDecision() {
        return Optional.ofNullable(decisions.peekLast());
    }

    /**
     * Takes another agent's word for something.
     *
     * Grounded in the latest episode, which is the moment of hearing it, so the trace shows
     * who said it and when. Hearsay starts at lower confidence than a sighting and is
     * overtaken naturally if the agent later sees the same thing for itself.
     */
    public void hear(String subject, String predicate, String object, long tick) {
        assertWithProvenance(subject, predicate, object, tick, Belief.Provenance.HEARSAY);
    }

    /**
     * Records something the agent worked out rather than saw.
     *
     * Grounded in the latest episode, which is where the agent was standing when it drew the
     * conclusion. That keeps the invariant that every belief names its evidence, and it is
     * honest about what the evidence is: not a sighting of the fact itself, but the moment
     * at which it was concluded. The INFERRED provenance carries the rest of the warning.
     */
    public void infer(String subject, String predicate, String object, long tick) {
        assertWithProvenance(subject, predicate, object, tick, Belief.Provenance.INFERRED);
    }

    private void assertWithProvenance(String subject, String predicate, String object, long tick,
                                      Belief.Provenance provenance) {
        if (episodic.size() == 0) {
            return;     // nothing perceived yet, so nothing to hang it on
        }
        long latestEpisode = episodic.size() - 1;
        SemanticMemory.Assertion assertion = semantic.assertTriple(subject, predicate, object,
                latestEpisode, tick, provenance);

        trace.believed(assertion.belief(), !assertion.isNew());
        if (assertion.contradicted() != null) {
            semantic.byId(assertion.contradicted().id())
                    .ifPresent(invalidated -> trace.revised(invalidated, assertion.belief()));
        }
    }

    /**
     * Writes this mind down so the next run starts where this one stopped.
     *
     * @param tick the clock this run reached, so the next one carries it on rather than
     *             restarting and putting new events before old ones
     */
    public void save(Path path, long tick) {
        MindSnapshot.save(path, name, tick, episodic, semantic);
    }

    /**
     * Wakes this mind up as the last run left it.
     *
     * Everything restored is written into this run's trace as well. Without that a replay
     * would show an agent acting on beliefs it is never seen to acquire, which is precisely
     * the question the trace exists to answer.
     *
     * @return the tick the previous run reached, or 0 for a mind with no past
     */
    public long restoreFrom(Path path) {
        MindSnapshot.Restored restored = MindSnapshot.load(path, episodic, semantic);
        if (restored == null) {
            return 0;
        }
        trace.resumed(restored.tick(), restored.episodes(), restored.beliefs());
        for (Belief belief : semantic.all()) {
            trace.carried(belief);
        }
        // Second pass, because a revision names the belief that replaced it and that one may
        // not have been written yet on the first.
        for (Belief belief : semantic.all()) {
            if (belief.invalidatedAt() != null && belief.supersededBy() != null) {
                semantic.byId(belief.supersededBy())
                        .ifPresent(replacement -> trace.revised(belief, replacement));
            }
        }
        return restored.tick();
    }

    /** The beliefs worth putting in front of a decision about {@code topic}. */
    public List<Belief> recall(String topic, long nowTick, int limit) {
        return Recall.mostRelevant(semantic, topic, nowTick, limit);
    }

    /** Pushes the trace to disk, so something following the file sees this step. */
    public void flush() {
        trace.flush();
    }

    public String name() {
        return name;
    }

    public EpisodicMemory episodic() {
        return episodic;
    }

    public SemanticMemory semantic() {
        return semantic;
    }

    public Trace trace() {
        return trace;
    }

    @Override
    public void close() {
        trace.close();
    }
}
