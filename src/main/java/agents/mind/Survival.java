package agents.mind;

import agents.Mind;
import agents.memory.Belief;
import agents.percept.Item;
import agents.world.WorldModel;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the agent alive by drinking things, and finds out what is worth drinking.
 *
 * <p>Nothing tells an agent a red potion heals. Item 2000000 is a number like any other, and
 * the only way to learn what it does is to drink one and watch. So when the agent is hurt it
 * reaches first for anything it has already seen restore health; if it knows of nothing, it
 * tries something drinkable it has never tried, and whatever happens to its health and mana
 * in the next few steps becomes what it believes about that item. A potion that healed it is
 * believed to heal; something that did nothing is believed not to, and is not tried again.
 *
 * <p>"Drinkable" is the one thing it is given: the kinds of item the client groups as potions
 * and food, which is where a player looks too. Everything else in the use bag - scrolls that
 * warp you to town, scrolls that upgrade equipment - is left alone, because an agent that
 * learnt by reading every scroll it found would spend its life being sent home.
 *
 * <p>When to drink is the one thing it takes from people rather than working out: where
 * recordings show how low people let their health get before reaching for a potion, it drinks
 * at that point instead of at half. Learning it by dying is possible but expensive.
 *
 * <p>This runs every step, before the policy, because being about to die is not something to
 * wait fifteen seconds on a model about.
 */
public final class Survival {

    /** Drink for health below half, for mana below a third. */
    static final double HP_LOW = 0.5;
    static final double MP_LOW = 0.3;

    /** How many steps to watch after drinking something new before deciding what it did. */
    static final int WATCH_STEPS = 4;

    /** Steps between drinks: the client will not use items faster than this anyway. */
    static final int BETWEEN_DRINKS = 2;

    /** An item being tried, and what health and mana were before it. */
    private record Trial(int itemId, String need, int hpBefore, int mpBefore, int mpMax, int stepsLeft) {
        Trial tick() {
            return new Trial(itemId, need, hpBefore, mpBefore, mpMax, stepsLeft - 1);
        }
    }

    private Trial trial;
    private int cooldown;

    /** How often the threshold people drink at is read again from what the agent believes. */
    static final int RETHINK_THRESHOLD_EVERY = 200;

    /** However people play, never wait until nearly dead, nor drink at a scratch. */
    static final double HP_LOW_FLOOR = 0.2;
    static final double HP_LOW_CEILING = 0.8;

    private double hpLow = HP_LOW;
    private int stepsSinceThreshold = RETHINK_THRESHOLD_EVERY;

    /**
     * What to drink this step, if anything. Also judges whatever was tried last.
     *
     * @return the item to use
     */
    public Optional<Integer> step(Mind mind, WorldModel world, long tick) {
        judge(mind, world, tick);
        if (cooldown > 0) {
            cooldown--;
            return Optional.empty();
        }
        if (world.isDead()) {
            return Optional.empty();
        }
        if (++stepsSinceThreshold >= RETHINK_THRESHOLD_EVERY) {
            hpLow = hpLowShownIn(mind.semantic().liveBeliefs());
            stepsSinceThreshold = 0;
        }
        String need = need(world, hpLow);
        if (need == null) {
            return Optional.empty();
        }

        List<Item> drinkable = world.inventory().carried(Item.USE).stream()
                .filter(item -> isDrinkable(item.itemId()))
                .toList();
        Optional<Item> known = drinkable.stream()
                .filter(item -> believes(mind, item.itemId(), need, true))
                .max(Comparator.comparingInt(Item::quantity));
        if (known.isPresent()) {
            cooldown = BETWEEN_DRINKS;
            return Optional.of(known.get().itemId());
        }
        Optional<Item> untried = drinkable.stream()
                .filter(item -> !triedFor(mind, item.itemId(), need))
                .findFirst();
        if (untried.isEmpty() || trial != null) {
            return Optional.empty();
        }
        int itemId = untried.get().itemId();
        trial = new Trial(itemId, need, world.hp(), world.stat("MP"), world.stat("MAXMP"), WATCH_STEPS);
        cooldown = BETWEEN_DRINKS;
        return Optional.of(itemId);
    }

    /**
     * The share of health people were seen drinking at, from {@code drunk_at_hp_percent}
     * beliefs, each counted as often as it was seen. Recorded to the tenth below, so the
     * middle of that tenth is the better guess. Half when nobody has been watched.
     */
    static double hpLowShownIn(List<agents.memory.Belief> beliefs) {
        double sum = 0;
        int seen = 0;
        for (agents.memory.Belief belief : beliefs) {
            if (!belief.predicate().equals("drunk_at_hp_percent")) {
                continue;
            }
            try {
                int percent = Integer.parseInt(belief.object());
                int times = belief.supportedBy().size();
                sum += (percent + 5) / 100.0 * times;
                seen += times;
            } catch (NumberFormatException ignored) {
                // not a percentage
            }
        }
        if (seen == 0) {
            return HP_LOW;
        }
        return Math.max(HP_LOW_FLOOR, Math.min(HP_LOW_CEILING, sum / seen));
    }

    /** What the agent is short of, or null. Health first: running out of it is dying. */
    static String need(WorldModel world) {
        return need(world, HP_LOW);
    }

    static String need(WorldModel world, double hpLow) {
        if (world.hp() >= 0 && world.maxHp() > 0 && world.hp() < world.maxHp() * hpLow) {
            return "hp";
        }
        int mp = world.stat("MP");
        int maxMp = world.stat("MAXMP");
        if (mp >= 0 && maxMp > 0 && mp < maxMp * MP_LOW) {
            return "mp";
        }
        return null;
    }

    /**
     * Decides what the item being tried did, from what happened to health and mana since.
     *
     * A rise is a yes. No rise by the end of the watch is a no - for health, which it was
     * drunk to restore; and for mana only if there was mana missing to restore, since a full
     * bar going nowhere says nothing.
     */
    private void judge(Mind mind, WorldModel world, long tick) {
        if (trial == null) {
            return;
        }
        String item = "item:" + trial.itemId();
        boolean hpRose = world.hp() > trial.hpBefore();
        boolean mpRose = world.stat("MP") > trial.mpBefore();
        if (hpRose || mpRose) {
            if (hpRose) {
                mind.infer(item, "restores_hp", "true", tick);
            }
            if (mpRose) {
                mind.infer(item, "restores_mp", "true", tick);
            }
            trial = null;
            return;
        }
        trial = trial.tick();
        if (trial.stepsLeft() > 0) {
            return;
        }
        mind.infer(item, "restores_hp", "false", tick);
        if (trial.mpBefore() >= 0 && trial.mpBefore() < trial.mpMax()) {
            mind.infer(item, "restores_mp", "false", tick);
        }
        trial = null;
    }

    /** Potions and food: the kinds of item the client groups for drinking and eating. */
    static boolean isDrinkable(int itemId) {
        int kind = itemId / 10000;
        return kind == 200 || kind == 201 || kind == 202;
    }

    private static boolean believes(Mind mind, int itemId, String need, boolean value) {
        return beliefAbout(mind, itemId, need)
                .anyMatch(b -> b.object().equals(String.valueOf(value)));
    }

    /** Whether it has already found out, one way or the other. */
    private static boolean triedFor(Mind mind, int itemId, String need) {
        return beliefAbout(mind, itemId, need).findAny().isPresent();
    }

    private static java.util.stream.Stream<Belief> beliefAbout(Mind mind, int itemId, String need) {
        String subject = "item:" + itemId;
        String predicate = "restores_" + need;
        return mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals(subject) && b.predicate().equals(predicate));
    }
}
