package agents.memory;

import agents.percept.Observation;

/**
 * One thing that happened, exactly as it was perceived.
 *
 * Episodes are never rewritten or reinterpreted. Whatever an agent later concludes, the raw
 * record of what it actually saw stays put, which is what lets a belief point at evidence
 * and a replay show the difference between what happened and what was made of it.
 */
public record Episode(long id, long tick, Observation observation) {

    /** Stable reference used in beliefs and in the trace. */
    public String ref() {
        return "e" + id;
    }
}
