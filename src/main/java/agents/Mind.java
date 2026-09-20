package agents;

import agents.memory.Belief;
import agents.memory.BeliefFormer;
import agents.memory.Episode;
import agents.memory.EpisodicMemory;
import agents.memory.Recall;
import agents.memory.SemanticMemory;
import agents.percept.Observation;
import agents.trace.Trace;

import java.util.List;

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
    private final Trace trace;

    public Mind(String name, Trace trace) {
        this.name = name;
        this.trace = trace;
    }

    /** Files an observation and records whatever it states outright. */
    public void take(Observation observation) {
        Episode episode = episodic.record(observation);
        trace.observed(episode);

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

    /** The beliefs worth putting in front of a decision about {@code topic}. */
    public List<Belief> recall(String topic, long nowTick, int limit) {
        return Recall.mostRelevant(semantic, topic, nowTick, limit);
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
