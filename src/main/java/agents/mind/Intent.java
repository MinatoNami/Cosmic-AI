package agents.mind;

import java.awt.Point;
import java.util.Map;

/**
 * Something an agent has decided to do.
 *
 * Coarse on purpose. The alternative - letting a policy emit packets - would mean a bad
 * decision could produce a malformed packet and drop the agent off the network, and would
 * make every policy carry protocol knowledge it has no business having. A closed set of
 * typed intents keeps the worst an LLM can do to "a sensible action at a silly moment".
 */
public sealed interface Intent {

    /** Short label for the trace. */
    String name();

    /** Fields for the trace, so a replay can show what was actually attempted. */
    Map<String, Object> detail();

    record MoveTo(Point destination) implements Intent {
        public String name() {
            return "MoveTo";
        }

        public Map<String, Object> detail() {
            return Map.of("x", destination.x, "y", destination.y);
        }
    }

    record Attack(int objectId, Point position) implements Intent {
        public String name() {
            return "Attack";
        }

        public Map<String, Object> detail() {
            return Map.of("target", objectId);
        }
    }

    record PickUp(int objectId, Point position) implements Intent {
        public String name() {
            return "PickUp";
        }

        public Map<String, Object> detail() {
            return Map.of("drop", objectId);
        }
    }

    record Say(String message) implements Intent {
        public String name() {
            return "Say";
        }

        public Map<String, Object> detail() {
            return Map.of("message", message);
        }
    }

    /** Walk into a portal. Where it leads is not known until it has been used. */
    record EnterPortal(String portalName, Point position) implements Intent {
        public String name() {
            return "EnterPortal";
        }

        public Map<String, Object> detail() {
            return Map.of("portal", portalName);
        }
    }

    record Wait() implements Intent {
        public String name() {
            return "Wait";
        }

        public Map<String, Object> detail() {
            return Map.of();
        }
    }
}
