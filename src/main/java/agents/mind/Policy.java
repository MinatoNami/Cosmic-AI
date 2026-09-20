package agents.mind;

import agents.Mind;
import agents.world.WorldModel;

import java.util.List;

/**
 * Chooses what an agent does next.
 *
 * The seam the whole project turns on: a hand-written policy and an LLM-backed one must be
 * swappable without anything else changing, so that "did the model actually help?" is a
 * question you can answer by running the same world twice.
 */
public interface Policy {

    /**
     * @param mind the agent's memory - a policy may read it, and must not reach past it for
     *             anything about the world
     */
    Decision decide(Mind mind, WorldModel world, long tick);

    /**
     * What was chosen, and enough about the choosing to write a trace worth reading.
     *
     * @param goal what the agent was trying to achieve, in its own words
     * @param consultedBeliefs belief refs that informed the choice
     * @param considered the options weighed, so a replay shows the road not taken
     * @param decidedBy the policy that actually chose, which is not always the one that was
     *                  asked - an LLM policy answers with its fallback's decision far more
     *                  often than with its own. Left null by a policy deciding for itself,
     *                  and filled in by the agent, which knows what it asked.
     * @param fellBackBecause why the asked policy did not decide this one, in a few words.
     *                        Null when nothing fell back. Without it a run of reflex
     *                        decisions is indistinguishable from a run of deliberated ones,
     *                        and "did the model help?" stops being answerable from the trace.
     */
    record Decision(Intent intent, String goal, List<String> consultedBeliefs, List<String> considered,
                    String decidedBy, String fellBackBecause) {

        /** For a policy that decides for itself and leaves the credit to its caller. */
        public Decision(Intent intent, String goal, List<String> consultedBeliefs, List<String> considered) {
            this(intent, goal, consultedBeliefs, considered, null, null);
        }

        /** The same decision, credited to whoever actually made it. */
        public Decision creditedTo(String policy, String fellBackBecause) {
            return new Decision(intent, goal, consultedBeliefs, considered, policy, fellBackBecause);
        }
    }

    /**
     * The model behind this policy, if it has one.
     *
     * Exposed because reading an NPC's words is language work rather than decision work, and
     * whatever else wants to do it should not have to be handed its own oracle and kept in
     * sync with how this one was configured.
     */
    default java.util.Optional<Oracle> oracle() {
        return java.util.Optional.empty();
    }

    String name();
}
