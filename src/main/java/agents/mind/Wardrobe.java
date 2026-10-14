package agents.mind;

import agents.percept.Item;
import agents.world.EquipInfo;
import agents.world.Inventory;
import agents.world.WorldModel;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Puts on the best thing the agent is carrying for each part of its body.
 *
 * <p>"Best" is a valuation this class is given, not one the agent arrived at: attack weighs
 * most, then the stat its job lives on, then the one that backs it up, with defence, health,
 * accuracy and speed counting for a little. It is the rule of thumb a new player is taught
 * in their first hour, and like the drinkable-item rule in {@link Survival} it is written
 * down here rather than pretended to be learnt. What stays honest is what it is applied to:
 * the numbers on the item's own tooltip, and the requirements printed on it, checked against
 * the agent's own level, job and stats before it asks - so it never asks the server for
 * something the server will refuse.
 *
 * <p>If the server refuses anyway, nothing moves, and after a few steps the item is set aside
 * so the agent does not stand there trying it on forever.
 */
public final class Wardrobe {

    /** Steps between changes. The server ignores inventory moves that come too close together. */
    static final int BETWEEN_CHANGES = 3;

    /** How long to wait for the server to move something before deciding it will not. */
    static final int PATIENCE = 5;

    /** A move asked for: the item, where from and to. */
    public record Change(int itemId, int fromSlot, int toSlot) {
    }

    private final IntFunction<EquipInfo> tooltips;
    private final Set<Integer> refused = new HashSet<>();
    private Change pending;
    private int waited;
    private int cooldown;

    public Wardrobe() {
        this(EquipInfo::of);
    }

    /** For tests, which cannot load Character.wz. */
    public Wardrobe(IntFunction<EquipInfo> tooltips) {
        this.tooltips = tooltips;
    }

    /** The one change worth making now, if any. */
    public Optional<Change> step(WorldModel world) {
        Inventory inventory = world.inventory();
        if (!inventory.known() || world.isDead() || world.level() < 0) {
            return Optional.empty();
        }
        if (pending != null) {
            if (inventory.wornAt(pending.toSlot()).map(Item::itemId).orElse(-1) == pending.itemId()) {
                pending = null;
            } else if (++waited >= PATIENCE) {
                refused.add(pending.itemId());
                pending = null;
            } else {
                return Optional.empty();
            }
        }
        if (cooldown > 0) {
            cooldown--;
            return Optional.empty();
        }

        Change best = null;
        double bestGain = 0;
        for (Item candidate : inventory.carried(Item.EQUIP)) {
            if (candidate.stats() == null || refused.contains(candidate.itemId())) {
                continue;
            }
            EquipInfo info = tooltips.apply(candidate.itemId());
            if (info.cash() || !canWear(info, world)) {
                continue;
            }
            int weapon = Fighting.kindOfWeapon(candidate.itemId());
            if (weapon != Fighting.NONE && !Fighting.weaponSuits(weapon, world.job())) {
                continue;       // a Bowman does not take up a sword, however sharp
            }
            for (int slot : info.slots()) {
                double gain = worthInHand(candidate, world)
                        - worthOfReplacing(slot, info, twoHanded(candidate.itemId()), inventory, world);
                if (gain > bestGain) {
                    bestGain = gain;
                    best = new Change(candidate.itemId(), candidate.slot(), slot);
                }
            }
        }
        if (best != null) {
            pending = best;
            waited = 0;
            cooldown = BETWEEN_CHANGES;
        }
        return Optional.ofNullable(best);
    }

    /**
     * What the agent loses by putting something here: whatever is worn there now; for an
     * overall, the trousers it replaces as well; and for trousers, an overall worn on top,
     * which the server takes off to make room.
     */
    private double worthOfReplacing(int slot, EquipInfo incoming, boolean incomingTwoHanded,
                                    Inventory inventory, WorldModel world) {
        double lost = inventory.wornAt(slot).map(item -> worthInHand(item, world)).orElse(0.0);
        if (incoming.overall()) {
            lost += inventory.wornAt(PANTS).map(item -> worth(item, world)).orElse(0.0);
        }
        if (slot == PANTS) {
            lost += inventory.wornAt(TOP)
                    .filter(top -> tooltips.apply(top.itemId()).overall())
                    .map(top -> worth(top, world))
                    .orElse(0.0);
        }
        // A shield and a weapon held in both hands cannot be worn together; putting on either
        // takes the other off. Not counting that, a Bowman put its shield on, lost its bow,
        // put the bow back on, lost its shield - every three seconds.
        if (slot == SHIELD) {
            lost += inventory.wornAt(WEAPON)
                    .filter(weapon -> twoHanded(weapon.itemId()))
                    .map(weapon -> worthInHand(weapon, world))
                    .orElse(0.0);
        }
        if (slot == WEAPON && incomingTwoHanded) {
            lost += inventory.wornAt(SHIELD).map(shield -> worth(shield, world)).orElse(0.0);
        }
        return lost;
    }

    /**
     * What something is worth worn, for a weapon also whether it can be used: one the job does
     * not fight with is worth nothing to keep on, and one with nothing to fire from it in the
     * bag is worth much less than one that can be used.
     */
    /** Weapons held in both hands: two-handed swords, axes and blunt weapons, spears, polearms, bows, crossbows, knuckles and guns. */
    static boolean twoHanded(int itemId) {
        int kind = (itemId / 10000) % 100;
        return itemId / 1000000 == 1 && (kind >= 40 && kind <= 46 || kind == 48 || kind == 49);
    }

    private static final int WEAPON = -11;
    private static final int SHIELD = -10;

    static double worthInHand(Item item, WorldModel world) {
        double worth = worth(item, world);
        int weapon = Fighting.kindOfWeapon(item.itemId());
        if (weapon == Fighting.NONE) {
            return worth;
        }
        if (!Fighting.weaponSuits(weapon, world.job())) {
            return 0;
        }
        return Fighting.isShooter(weapon) && !Fighting.hasAmmunition(world, weapon) ? worth * EMPTY_SHOOTER : worth;
    }

    /** Whether it holds or carries a weapon its job fights with. */
    static boolean hasAUsableWeapon(WorldModel world) {
        return world.inventory().worn().stream().filter(item -> item.slot() == -11)
                .anyMatch(item -> Fighting.weaponSuits(Fighting.kindOfWeapon(item.itemId()), world.job()))
                || world.inventory().carried(Item.EQUIP).stream()
                .anyMatch(item -> Fighting.kindOfWeapon(item.itemId()) != Fighting.NONE
                        && Fighting.weaponSuits(Fighting.kindOfWeapon(item.itemId()), world.job()));
    }

    /** Whether it needs to buy a weapon: one is in hand, but not one its job fights with. */
    public static boolean needsAWeapon(WorldModel world) {
        return world.inventory().known() && world.job() > 0 && !hasAUsableWeapon(world);
    }

    /** What a bow, claw or gun is worth with nothing to fire from it. */
    private static final double EMPTY_SHOOTER = 0.3;

    private static final int TOP = -5;
    private static final int PANTS = -6;

    /** Whether the tooltip's requirements are all met - the ones the client prints in red. */
    boolean canWear(EquipInfo info, WorldModel world) {
        return info.slots().length > 0
                && world.level() >= info.reqLevel()
                && info.allowsJob(Math.max(0, world.job()))
                && total("STR", world) >= info.reqStr()
                && total("DEX", world) >= info.reqDex()
                && total("INT", world) >= info.reqInt()
                && total("LUK", world) >= info.reqLuk();
    }

    /** A stat with what is already worn added, which is what the server checks against. */
    private static int total(String stat, WorldModel world) {
        int base = Math.max(0, world.stat(stat));
        int worn = world.inventory().worn().stream()
                .filter(item -> item.stats() != null)
                .mapToInt(item -> switch (stat) {
                    case "STR" -> item.stats().str();
                    case "DEX" -> item.stats().dex();
                    case "INT" -> item.stats().intel();
                    default -> item.stats().luk();
                })
                .sum();
        return base + worn;
    }

    /**
     * What an item is worth to this agent, by the rule of thumb above.
     *
     * The job decides which attack and which stats matter: a magician fights with magic and
     * INT, a thief with LUK, a bowman with DEX, and a warrior, pirate or beginner with STR.
     */
    static double worth(Item item, WorldModel world) {
        Item.EquipStats s = item.stats();
        if (s == null) {
            return 0;
        }
        int family = (Math.max(0, world.job()) % 1000) / 100;
        int attack;
        int primary;
        int secondary;
        switch (family) {
            case 2 -> {
                attack = s.matk();
                primary = s.intel();
                secondary = s.luk();
            }
            case 3 -> {
                attack = s.watk();
                primary = s.dex();
                secondary = s.str();
            }
            case 4 -> {
                attack = s.watk();
                primary = s.luk();
                secondary = s.dex();
            }
            default -> {
                attack = s.watk();
                primary = s.str();
                secondary = s.dex();
            }
        }
        return 4.0 * attack
                + 1.5 * primary
                + 0.75 * secondary
                + 0.1 * (s.wdef() + s.mdef())
                + 0.05 * (s.hp() + s.mp())
                + 0.25 * (s.acc() + s.avoid())
                + 0.2 * (s.speed() + s.jump());
    }
}
