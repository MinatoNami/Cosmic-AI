package agents.body;

import agents.percept.Item;
import agents.percept.Observation;
import agents.protocol.ClientPackets;
import agents.world.Inventory;
import net.packet.Packet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The agent's keyboard: which key does what, and what pressing one sends.
 *
 * A player drinks a potion by pressing the key it is bound to, and the client turns that
 * into "use the first stack of this item". An agent does the same thing here: a potion is
 * bound to a key once, the binding is saved with the character like a player's, and after
 * that drinking one is pressing its key - so the keyboard window in a watching GM's client
 * shows what the agent has chosen to keep to hand.
 *
 * <p>The bindings are the server's, read on entering the world. A rebinding is the client's
 * own decision, so it is recorded here as it is sent; the server does not echo it back.
 */
public final class Keyboard {

    /** A key bound to an item from the use bag. */
    public static final int ITEM = 2;

    /**
     * Where a player keeps potions: the block above the arrow keys, which nothing is bound
     * to by default. Delete, End, Page Down, Insert, Home, Page Up.
     */
    static final List<Integer> POTION_KEYS = List.of(83, 79, 81, 82, 71, 73);

    private final Map<Integer, Observation.KeysBound.Binding> keys = new HashMap<>();
    private boolean known;

    public void update(Observation observation) {
        if (observation instanceof Observation.KeysBound bound) {
            keys.clear();
            keys.putAll(bound.keys());
            known = true;
        }
    }

    /** The key this item is bound to, if any. */
    public Optional<Integer> keyFor(int itemId) {
        return keys.entrySet().stream()
                .filter(e -> e.getValue().type() == ITEM && e.getValue().action() == itemId)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Binds an item to a free potion key and returns the packet that does it, or nothing if
     * it is bound already, the keyboard has not been described yet, or every potion key is
     * taken - in which case the one holding the item used least recently is not worth
     * guessing at, and the agent simply goes without.
     */
    public Optional<Packet> bind(int itemId) {
        if (!known || keyFor(itemId).isPresent()) {
            return Optional.empty();
        }
        for (int key : POTION_KEYS) {
            if (!keys.containsKey(key)) {
                keys.put(key, new Observation.KeysBound.Binding(ITEM, itemId));
                return Optional.of(ClientPackets.bindKeys(Map.of(key, new int[]{ITEM, itemId})));
            }
        }
        return Optional.empty();
    }

    /**
     * Presses a key. For a key holding an item this uses the first stack of it in the use
     * bag, exactly as the client would; for an empty key, or an item no longer carried,
     * nothing happens.
     */
    public Optional<Packet> press(int key, Inventory inventory) {
        Observation.KeysBound.Binding binding = keys.get(key);
        if (binding == null || binding.type() != ITEM) {
            return Optional.empty();
        }
        return inventory.firstOf(binding.action())
                .filter(item -> item.type() == Item.USE)
                .map(item -> ClientPackets.useItem(item.slot(), item.itemId()));
    }
}
