package agents.world;

import agents.percept.Item;
import agents.percept.Observation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the agent is carrying and wearing, as the server last described it.
 *
 * Built only from observations: the bags described on entering the world, then every change
 * after. Nothing is assumed about what happens when the agent asks for something - asking
 * to put a hat on changes nothing here until the server says the hat moved - so this can
 * never believe an action worked that did not.
 */
public final class Inventory {

    /** Bag and slot to what is there. Worn items live in the equipment bag at negative slots. */
    private final Map<Integer, Map<Integer, Item>> bags = new HashMap<>();
    private final Map<Integer, Integer> slotLimits = new HashMap<>();
    private int meso = -1;

    public void update(Observation observation) {
        switch (observation) {
            case Observation.InventoryShown shown -> {
                bags.clear();
                slotLimits.putAll(shown.slotLimits());
                meso = shown.meso();
                shown.items().forEach(this::put);
            }
            case Observation.InventoryChanged changed -> changed.changes().forEach(this::apply);
            case Observation.StatsChanged stats -> {
                Integer mesos = stats.stats().get("MESO");
                if (mesos != null) {
                    meso = mesos;
                }
            }
            default -> {
            }
        }
    }

    private void apply(Observation.InventoryChanged.Change change) {
        Map<Integer, Item> bag = bag(change.type());
        switch (change.kind()) {
            case ADDED -> put(change.item());
            case RESIZED -> {
                Item item = bag.get(change.slot());
                if (item != null) {
                    bag.put(change.slot(), item.withQuantity(change.quantity()));
                }
            }
            case MOVED -> {
                // A move onto an occupied slot swaps the two, which is how putting on a new
                // hat takes the old one off.
                Item moving = bag.remove(change.slot());
                Item displaced = bag.remove(change.toSlot());
                if (moving != null) {
                    bag.put(change.toSlot(), moving.at(change.toSlot()));
                }
                if (displaced != null) {
                    bag.put(change.slot(), displaced.at(change.slot()));
                }
            }
            case REMOVED -> bag.remove(change.slot());
        }
    }

    private void put(Item item) {
        bag(item.type()).put(item.slot(), item);
    }

    private Map<Integer, Item> bag(int type) {
        return bags.computeIfAbsent(type, t -> new HashMap<>());
    }

    /** Whether the bags have been described yet. Until they are, the agent knows nothing here. */
    public boolean known() {
        return !slotLimits.isEmpty();
    }

    /** What is carried in one bag, by slot, not counting anything worn. */
    public List<Item> carried(int type) {
        List<Item> items = new ArrayList<>();
        for (Item item : bag(type).values()) {
            if (!item.isWorn()) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparingInt(Item::slot));
        return items;
    }

    public List<Item> worn() {
        return bag(Item.EQUIP).values().stream()
                .filter(Item::isWorn)
                .sorted(Comparator.comparingInt(Item::slot))
                .toList();
    }

    public Optional<Item> wornAt(int slot) {
        return Optional.ofNullable(bag(Item.EQUIP).get(slot)).filter(Item::isWorn);
    }

    public int freeSlots(int type) {
        return Math.max(0, slotLimits.getOrDefault(type, 0) - carried(type).size());
    }

    /**
     * Whether a bag has no room left. Only the bags things are picked up into count: a full
     * equipment, use or etc bag is what turns a drop into something that cannot be taken.
     */
    public boolean isFull(int type) {
        return known() && freeSlots(type) == 0;
    }

    public boolean anyBagFull() {
        return isFull(Item.EQUIP) || isFull(Item.USE) || isFull(Item.ETC);
    }

    /** How many of this item are carried, across every stack of it. */
    public int count(int itemId) {
        return carried(Item.typeOf(itemId)).stream()
                .filter(item -> item.itemId() == itemId)
                .mapToInt(Item::quantity)
                .sum();
    }

    /** The first stack of this item, which is the one the client uses when asked for it. */
    public Optional<Item> firstOf(int itemId) {
        return carried(Item.typeOf(itemId)).stream()
                .filter(item -> item.itemId() == itemId)
                .findFirst();
    }

    /** Mesos held, or -1 if never told. */
    public int meso() {
        return meso;
    }

    /** What everything worn adds together, which is what the character actually has on. */
    public int wornDefence() {
        return worn().stream()
                .filter(item -> item.stats() != null)
                .mapToInt(item -> item.stats().wdef())
                .sum();
    }
}
