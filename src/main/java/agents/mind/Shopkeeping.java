package agents.mind;

import agents.Mind;
import agents.percept.Item;
import agents.percept.Observation;
import agents.protocol.ClientPackets;
import agents.world.EquipInfo;
import agents.world.Inventory;
import agents.world.WorldModel;
import net.packet.Packet;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * What to do at a shop counter: sell what is spare, stock up on what heals, walk away.
 *
 * <p>Spare is everything in the etc bag - things monsters drop, which the agent has no use
 * for that it knows of - equipment no better than what it wears, and anything drinkable it
 * has found does nothing. Kept is anything it wears or might yet wear, anything drinkable it
 * has not tried, and every scroll: nothing here decides a scroll is worthless, because the
 * agent has no way to have found out.
 *
 * <p>Stocking up is on what the agent has itself seen heal it. Knowing of nothing, it buys a
 * few of the cheapest drinkable thing on offer, to find out - the same experiment
 * {@link Survival} runs on what it finds, paid for instead.
 *
 * <p>One transaction a step, so a watcher sees it happen, and so each sale's money has
 * arrived before anything is bought with it.
 */
public final class Shopkeeping {

    /** How many of what heals to carry out of a shop. */
    static final int HEALERS_WANTED = 30;
    static final int MANA_WANTED = 10;

    /** How many of something unknown to buy, to try. */
    static final int TO_TRY = 3;

    /** Buying one kind more than this at once is refused; the client caps it too. */
    static final int MOST_AT_ONCE = 100;

    private final IntFunction<EquipInfo> tooltips;
    private Observation.ShopOpened open;
    private final Deque<Item> toSell = new ArrayDeque<>();
    private boolean boughtYet;

    public Shopkeeping() {
        this(EquipInfo::of);
    }

    /** For tests, which cannot load Character.wz. */
    public Shopkeeping(IntFunction<EquipInfo> tooltips) {
        this.tooltips = tooltips;
    }

    /** A shop has opened in front of the agent: work out what is spare. */
    public void opened(Observation.ShopOpened shop, WorldModel world, Mind mind) {
        open = shop;
        boughtYet = false;
        toSell.clear();
        toSell.addAll(spare(world, mind));
    }

    public boolean atCounter() {
        return open != null;
    }

    /**
     * The next thing to do at the counter, or the packet walking away from it once there is
     * nothing left. Null when no shop is open.
     */
    public Packet next(WorldModel world, Mind mind) {
        if (open == null) {
            return null;
        }
        Inventory inventory = world.inventory();
        while (!toSell.isEmpty()) {
            Item item = toSell.poll();
            // Still there, and still the same thing: the bag may have moved since the plan.
            Optional<Item> now = inventory.carried(item.type()).stream()
                    .filter(i -> i.slot() == item.slot() && i.itemId() == item.itemId())
                    .findFirst();
            if (now.isPresent()) {
                return ClientPackets.sellToShop(item.slot(), item.itemId(), now.get().quantity());
            }
        }
        if (!boughtYet) {
            boughtYet = true;
            Optional<Packet> purchase = purchase(world, mind);
            if (purchase.isPresent()) {
                return purchase.get();
            }
        }
        open = null;
        return ClientPackets.leaveShop();
    }

    /** What is spare, by the rules above, in the order it will be sold. */
    List<Item> spare(WorldModel world, Mind mind) {
        Inventory inventory = world.inventory();
        List<Item> spare = new java.util.ArrayList<>(inventory.carried(Item.ETC));
        for (Item equipment : inventory.carried(Item.EQUIP)) {
            if (noBetterThanWorn(equipment, world)) {
                spare.add(equipment);
            }
        }
        for (Item usable : inventory.carried(Item.USE)) {
            if (Survival.isDrinkable(usable.itemId())
                    && believes(mind, usable.itemId(), "restores_hp", "false")
                    && !believes(mind, usable.itemId(), "restores_mp", "true")) {
                spare.add(usable);
            }
        }
        return spare;
    }

    /**
     * Equipment is spare when wearing it could never help: nothing better than what is on
     * that part of the body, or not for this job at all. Something better that the agent is
     * simply not yet level enough for is kept.
     */
    private boolean noBetterThanWorn(Item equipment, WorldModel world) {
        EquipInfo info = tooltips.apply(equipment.itemId());
        if (info.slots().length == 0 || !info.allowsJob(Math.max(0, world.job()))) {
            return true;
        }
        // Valued as worn, job and all: Athena's bow looked worse than the spear a Bowman was
        // holding, and was sold, and the Bowman never shot an arrow again.
        double worth = Wardrobe.worthInHand(equipment, world);
        for (int slot : info.slots()) {
            double worn = world.inventory().wornAt(slot).map(item -> Wardrobe.worthInHand(item, world)).orElse(0.0);
            if (worth > worn) {
                return false;
            }
        }
        return true;
    }

    /** One purchase: topping up on what heals, or else something to try. */
    private Optional<Packet> purchase(WorldModel world, Mind mind) {
        int meso = world.inventory().meso();
        if (meso <= 0) {
            return Optional.empty();
        }
        Optional<Packet> weapon = weapon(world, meso);
        if (weapon.isPresent()) {
            return weapon;
        }
        Optional<Packet> ammunition = ammunition(world, meso);
        if (ammunition.isPresent()) {
            return ammunition;
        }
        Optional<Packet> healers = topUp(world, mind, meso, "restores_hp", HEALERS_WANTED);
        if (healers.isPresent()) {
            return healers;
        }
        boolean knowsAHealer = open.items().stream()
                .anyMatch(item -> believes(mind, item.itemId(), "restores_hp", "true"));
        if (!knowsAHealer) {
            Optional<Observation.ShopOpened.ShopItem> untried = open.items().stream()
                    .filter(item -> Survival.isDrinkable(item.itemId()))
                    .filter(item -> !triedFor(mind, item.itemId()))
                    .filter(item -> item.price() > 0 && item.price() * TO_TRY <= meso)
                    .min(Comparator.comparingInt(Observation.ShopOpened.ShopItem::price));
            if (untried.isPresent()) {
                return Optional.of(ClientPackets.buyFromShop(untried.get().index(), untried.get().itemId(), TO_TRY));
            }
        }
        return topUp(world, mind, meso, "restores_mp", MANA_WANTED);
    }

    /** Buys enough of the cheapest thing known to do this to carry {@code wanted}, if affordable. */
    /**
     * Something to fire, for a bow, crossbow, claw or gun running low: the cheapest on the
     * shelf that fits, arrows and bolts by the hundred, stars and bullets by the set, and never
     * more than most of the money - something to heal with matters too.
     */
    private Optional<Packet> ammunition(WorldModel world, int meso) {
        int weapon = Fighting.weaponKind(world);
        if (!Fighting.wantsAmmunition(world)) {
            return Optional.empty();
        }
        int budget = (int) (meso * AMMUNITION_BUDGET);
        return open.items().stream()
                .filter(item -> Fighting.fires(weapon, item.itemId()))
                .filter(item -> item.price() > 0 && item.price() <= budget)
                .min(Comparator.comparingInt(Observation.ShopOpened.ShopItem::price))
                .map(item -> {
                    boolean bundled = weapon == Fighting.BOW || weapon == Fighting.CROSSBOW;
                    int quantity = bundled
                            ? Math.min(ARROWS_WANTED - Fighting.ammunition(world, weapon), budget / item.price())
                            : 1;
                    return ClientPackets.buyFromShop(item.index(), item.itemId(), Math.max(1, quantity));
                });
    }

    /**
     * A weapon its job fights with, when it has none: the cheapest on the shelf it can wear,
     * with at most most of its money. The wardrobe puts it on once it is in the bag.
     */
    private Optional<Packet> weapon(WorldModel world, int meso) {
        if (!Wardrobe.needsAWeapon(world)) {
            return Optional.empty();
        }
        int budget = (int) (meso * AMMUNITION_BUDGET);
        Wardrobe fitting = new Wardrobe(tooltips);
        return open.items().stream()
                .filter(item -> Fighting.kindOfWeapon(item.itemId()) != Fighting.NONE)
                .filter(item -> Fighting.weaponSuits(Fighting.kindOfWeapon(item.itemId()), world.job()))
                .filter(item -> item.price() > 0 && item.price() <= budget)
                .filter(item -> fitting.canWear(tooltips.apply(item.itemId()), world))
                .min(Comparator.comparingInt(Observation.ShopOpened.ShopItem::price))
                .map(item -> ClientPackets.buyFromShop(item.index(), item.itemId(), 1));
    }

    static final int ARROWS_WANTED = 1000;
    static final double AMMUNITION_BUDGET = 0.6;

    private Optional<Packet> topUp(WorldModel world, Mind mind, int meso, String does, int wanted) {
        return open.items().stream()
                .filter(item -> believes(mind, item.itemId(), does, "true"))
                .filter(item -> item.price() > 0)
                .min(Comparator.comparingInt(Observation.ShopOpened.ShopItem::price))
                .flatMap(item -> {
                    int missing = wanted - world.inventory().count(item.itemId());
                    int affordable = meso / item.price();
                    int quantity = Math.min(Math.min(missing, affordable), MOST_AT_ONCE);
                    return quantity > 0
                            ? Optional.of(ClientPackets.buyFromShop(item.index(), item.itemId(), quantity))
                            : Optional.empty();
                });
    }

    private static boolean believes(Mind mind, int itemId, String predicate, String value) {
        String subject = "item:" + itemId;
        return mind.semantic().liveBeliefs().stream()
                .anyMatch(b -> b.subject().equals(subject) && b.predicate().equals(predicate)
                        && b.object().equals(value));
    }

    private static boolean triedFor(Mind mind, int itemId) {
        String subject = "item:" + itemId;
        return mind.semantic().liveBeliefs().stream()
                .anyMatch(b -> b.subject().equals(subject) && b.predicate().startsWith("restores_"));
    }
}
