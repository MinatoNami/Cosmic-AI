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

    /** The agent arrived in a map, standing on the portal numbered {@code spawnPoint}. */
    record MapEntered(long tick, int mapId, int spawnPoint) implements Observation {
    }

    /**
     * The agent's own character, as the server describes it on entering the world.
     * {@code spawnPoint} is the portal it is standing at, as in {@link MapEntered}, and
     * {@code stats} its own numbers under the names a {@link StatsChanged} uses - health
     * among them, which an agent otherwise did not know until it first changed.
     */
    record SelfDescribed(long tick, int characterId, String name, int level, int job,
                         int mapId, int spawnPoint, Map<String, Integer> stats) implements Observation {
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

    /**
     * A monster went out of sight without dying: out of range, captured, or wiped and
     * re-sent by the server when the agent finished arriving in a map. It may well still be
     * there, so nothing about it should be concluded from its going.
     */
    record MonsterVanished(long tick, int objectId) implements Observation {
    }

    /** Something with an object id moved. Covers other players and monsters alike. */
    record ThingMoved(long tick, int objectId, Point position) implements Observation {
    }

    /** The server refused a pick-up because there is no room left to carry it. */
    record InventoryFull(long tick) implements Observation {
    }

    record DropAppeared(long tick, int objectId, int itemId, boolean meso,
                        Point position) implements Observation {
    }

    /**
     * A drop left the ground. {@code takenBy} is the character who picked it up, or
     * {@link #NOBODY} when it simply expired - which is how an agent tells its own pick-up
     * from a rival's.
     */
    record DropTaken(long tick, int objectId, int takenBy) implements Observation {
        public static final int NOBODY = -1;
    }

    /**
     * How much of a monster is left, as the bar over its head shows it: a percentage, not a
     * number. Sent to whoever hit it, which makes it the first sign an attack did anything.
     */
    record MonsterHurt(long tick, int objectId, int hpPercent) implements Observation {
    }

    /**
     * A monster walked into the agent and hurt it.
     *
     * The one observation that does not arrive from the server: the agent's own client
     * noticed the collision, as a player's does, and reported it. The mind gets the same
     * thing a player sees - what hit it and for how much - and the health it lost arrives
     * separately, from the server, like any other change.
     */
    record TouchedBy(long tick, int objectId, int monsterId, int damage) implements Observation {
    }

    /**
     * Everything the agent carries and wears, and how big each bag is, as it enters the
     * world. {@code slotLimits} is keyed by bag, as {@link Item#type()}.
     */
    record InventoryShown(long tick, int meso, Map<Integer, Integer> slotLimits,
                          java.util.List<Item> items) implements Observation {
    }

    /** What the agent carries or wears changed. */
    record InventoryChanged(long tick, java.util.List<Change> changes) implements Observation {

        /**
         * One change. Moving something to a negative slot is putting it on, and from one is
         * taking it off - the server has no separate message for either.
         */
        public record Change(Kind kind, int type, int slot, int toSlot, int quantity, Item item) {

            public enum Kind { ADDED, RESIZED, MOVED, REMOVED }

            public static Change added(Item item) {
                return new Change(Kind.ADDED, item.type(), item.slot(), item.slot(), item.quantity(), item);
            }

            public static Change resized(int type, int slot, int quantity) {
                return new Change(Kind.RESIZED, type, slot, slot, quantity, null);
            }

            public static Change moved(int type, int from, int to) {
                return new Change(Kind.MOVED, type, from, to, 0, null);
            }

            public static Change removed(int type, int slot) {
                return new Change(Kind.REMOVED, type, slot, slot, 0, null);
            }
        }
    }

    /**
     * What each key on the agent's keyboard does, as the server stores it for the character.
     * Sent once on entering the world; changes after that are the agent's own doing.
     */
    record KeysBound(long tick, Map<Integer, Binding> keys) implements Observation {

        /** What a key is bound to: a kind (an item, a skill, a menu) and which one. */
        public record Binding(int type, int action) {
        }
    }

    /**
     * An NPC opened a shop: what it sells, in order, and for how much. The order matters -
     * buying names an item by its place in this list.
     */
    record ShopOpened(long tick, int npcId, java.util.List<ShopItem> items) implements Observation {

        public record ShopItem(int index, int itemId, int price) {
        }
    }

    /** Somebody else in the map was hurt, by how much, and by what kind of monster (0 if none). */
    record PlayerHurt(long tick, int characterId, int damage, int monsterId) implements Observation {
    }

    /**
     * The agent's experience went up by this much.
     *
     * The stat update says what the total now is; this says what just changed and arrives
     * next to whatever caused it, which is what makes the cause findable.
     */
    record ExpGained(long tick, int amount) implements Observation {
    }

    /** The agent's purse went up by this much. */
    record MesoGained(long tick, int amount) implements Observation {
    }

    /** Something went into the agent's bag. */
    record ItemGained(long tick, int itemId, int quantity) implements Observation {
    }

    record ChatHeard(long tick, int speakerId, String text) implements Observation {
    }

    /** A private message. Unlike map chat this carries a name, not a character id. */
    record WhisperHeard(long tick, String speakerName, String text) implements Observation {
    }

    record NoticeShown(long tick, String text) implements Observation {
    }

    /**
     * An NPC said something. {@code style} is how the client would render it - whether it
     * wants a yes, a choice, or just an acknowledgement - which is all an agent needs to
     * answer without understanding a word.
     */
    record DialogueShown(long tick, int npcId, String text, int style) implements Observation {
    }

    /** A quest changed state. 0 not started, 1 started, 2 completed. */
    record QuestStateChanged(long tick, int questId, int state) implements Observation {
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

    /**
     * An episode read back from a saved mind rather than perceived in this run.
     *
     * Only what it was and how it read are kept, not the original's structured fields. The
     * reason to carry episodes across runs at all is to keep beliefs pointing at the evidence
     * that produced them; nothing re-derives anything from these, and no belief former sees
     * them, because they arrive already believed.
     */
    record Recalled(long tick, String type, String detail) implements Observation {
    }
}
