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

    /** Walk up to an NPC and say hello, without knowing what it is for. */
    record TalkTo(int objectId, int npcId, Point position) implements Intent {
        public String name() {
            return "TalkTo";
        }

        public Map<String, Object> detail() {
            return Map.of("npc", npcId);
        }
    }

    /** Ask an NPC to start something. What it involves is not known until it starts. */
    record StartQuest(int questId, int npcId, Point position) implements Intent {
        public String name() {
            return "StartQuest";
        }

        public Map<String, Object> detail() {
            return Map.of("quest", questId, "npc", npcId);
        }
    }

    /**
     * Go back to an NPC and try to hand a quest in.
     *
     * Trying is the whole mechanism: the agent does not know what the quest wanted, so it
     * offers and finds out. A refusal costs nothing and looks like nothing happening.
     */
    record CompleteQuest(int questId, int npcId, Point position) implements Intent {
        public String name() {
            return "CompleteQuest";
        }

        public Map<String, Object> detail() {
            return Map.of("quest", questId, "npc", npcId);
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
