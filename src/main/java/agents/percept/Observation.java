package agents.percept;

import java.awt.Point;
import java.util.Map;

/**
 * Something an agent noticed. This is the entire surface through which the world reaches an
 * agent's mind - nothing else may.
 *
 * Note what these carry and what they do not. A monster arrives as an id and a position, not
 * as "a snail, weak, worth 3 exp". An item is a number. An NPC is a number. That an id means
 * anything at all is a hypothesis the agent has to form from what follows when it acts on
 * one, which is the whole point of the exercise. Names that would give the game away are
 * deliberately absent even where the packet could supply them.
 */
public sealed interface Observation {

    /** Tick at which this was perceived, set by the perceiver. */
    long tick();

    /** The agent arrived in a map. */
    record MapEntered(long tick, int mapId, int spawnPoint) implements Observation {
    }

    /** The agent's own character, as the server describes it on entering the world. */
    record SelfDescribed(long tick, int characterId, String name, int level, int job,
                         int mapId) implements Observation {
    }

    /** One or more of the agent's own stats changed. Keys are the server's stat names. */
    record StatsChanged(long tick, Map<String, Integer> stats) implements Observation {
    }

    record PlayerAppeared(long tick, int characterId, String name, int level) implements Observation {
    }

    record PlayerLeft(long tick, int characterId) implements Observation {
    }

    record NpcAppeared(long tick, int objectId, int npcId, Point position) implements Observation {
    }

    record MonsterAppeared(long tick, int objectId, int monsterId, Point position) implements Observation {
    }

    record MonsterDied(long tick, int objectId) implements Observation {
    }

    /** Something with an object id moved. Covers other players and monsters alike. */
    record ThingMoved(long tick, int objectId, Point position) implements Observation {
    }

    record DropAppeared(long tick, int objectId, int itemId, boolean meso,
                        Point position) implements Observation {
    }

    record ChatHeard(long tick, int speakerId, String text) implements Observation {
    }

    record NoticeShown(long tick, String text) implements Observation {
    }

    /**
     * A packet the decoder does not handle.
     *
     * Kept rather than dropped for two reasons. An agent receiving something it cannot
     * interpret is in an honest position - it knows that something happened - and the
     * histogram of what turns up here is how we decide what to decode next.
     */
    record Unrecognised(long tick, int opcode, String opcodeName, int bytes) implements Observation {
    }
}
