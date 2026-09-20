package agents.memory;

import java.util.List;

/**
 * A decision as it was written to the trace.
 *
 * Kept in memory as well as on disk so an agent can answer for itself while it is still
 * playing. That it is the same record either way is the point: when an agent says why it did
 * something, it is reading what it wrote at the time, not composing a story afterwards.
 *
 * @param usedBeliefs refs of the beliefs consulted, resolvable against semantic memory
 */
public record DecisionRecord(long tick, String actionRef, String intent, String goal,
                             List<String> usedBeliefs) {

    public DecisionRecord {
        usedBeliefs = List.copyOf(usedBeliefs);
    }
}
