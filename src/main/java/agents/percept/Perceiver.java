package agents.percept;

import agents.net.PacketInbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The boundary between the wire and an agent's mind.
 *
 * Everything an agent knows passes through here, which is what makes "starts with zero
 * knowledge" checkable rather than aspirational: if a fact did not arrive as an
 * {@link Observation} from this class, the agent has no way to hold it.
 *
 * Ticks are assigned here, monotonically per agent, and every downstream record is stamped
 * with one so the trace can be replayed in order.
 */
public class Perceiver {
    private final ObservationDecoder decoder = new ObservationDecoder();
    private final Map<String, Integer> unrecognisedCounts = new TreeMap<>();
    private long tick;

    /** Takes everything that has arrived and interprets it. */
    public List<Observation> perceive(PacketInbox inbox) {
        List<Observation> observations = new ArrayList<>();

        PacketInbox.Received received;
        while ((received = inbox.poll()) != null) {
            Observation observation = decoder.decode(++tick, received.opcode(), received.packet());
            if (observation instanceof Observation.Unrecognised unrecognised) {
                unrecognisedCounts.merge(unrecognised.opcodeName(), 1, Integer::sum);
            }
            observations.add(observation);
        }
        return observations;
    }

    public long currentTick() {
        return tick;
    }

    /**
     * How often each undecoded packet turned up, most frequent first. This is the work list
     * for extending the decoder - ranked by what agents actually meet rather than by guess.
     */
    public Map<String, Integer> unrecognisedCounts() {
        return Map.copyOf(unrecognisedCounts);
    }
}
