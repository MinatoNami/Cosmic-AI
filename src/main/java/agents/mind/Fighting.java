package agents.mind;

import agents.percept.Item;
import agents.world.SkillBook;
import agents.world.WorldModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * How to hit something, and what to cast on oneself first.
 *
 * <p>Every attack used to be a plain swing with no skill: a Bowman walked up to a snail and
 * hit it with the bow, a Magician knew Magic Claw and never once cast it, and every skill
 * point spent on an attack bought nothing. A player uses the strongest attack they have and
 * can pay for, from as far off as it reaches, and keeps their buffs up.
 *
 * <p>What is known is the agent's own: the skills its skill window lists and the levels it
 * put into them, its weapon, what it carries to throw or fire, and its MP. What a skill does
 * at a level is read from the skill data, as the skill window's own description is.
 */
public final class Fighting {

    private Fighting() {
    }

    public enum Kind {
        /** A swing, close in. */
        MELEE,
        /** Fired or thrown: needs a bow, crossbow or claw, and something to shoot. */
        RANGED,
        /** A spell. */
        MAGIC
    }

    /**
     * One attack: the skill (0 for a plain one), at what level, how, how many damage lines,
     * and how much harder than a plain hit each line is.
     *
     * @param damagePercent percent of a plain physical hit; 100 for a plain attack
     * @param magic         the spell's magic attack, for a spell
     */
    public record Attack(int skillId, int level, Kind kind, int lines, int damagePercent, int magic,
                         int mpCost) {

        static final Attack PLAIN = new Attack(0, 0, Kind.MELEE, 1, 100, 0, 0);
    }

    /** How far a shot or a spell reaches, across, and how far above or below. */
    public static final int RANGED_REACH = 350;
    public static final int RANGED_HEIGHT = 70;

    /** MP kept back for a buff or an emergency, as a share of the most it can hold. */
    private static final double MP_RESERVE = 0.15;

    /** Skills that are cast but are no use to an agent that has to keep hitting things. */
    private static final Set<Integer> NOT_FOR_US = Set.of(
            4001003,        // Dark Sight: cannot attack while hidden
            4001002,        // Disorder: a curse on a monster, not on oneself
            2101002, 2201002, 2301001,  // Teleport
            2311002);       // Mystic Door

    /** The strongest attack it can make right now. */
    public static Attack choose(WorldModel world) {
        int weapon = weaponKind(world);
        boolean shooter = weapon == BOW || weapon == CROSSBOW || weapon == CLAW || weapon == GUN;
        boolean ammunition = shooter && hasAmmunition(world, weapon);
        int mp = Math.max(0, world.stat("MP"));
        int reserve = (int) (Math.max(0, world.stat("MAXMP")) * MP_RESERVE);

        List<Attack> options = new ArrayList<>();
        options.add(shooter && ammunition ? new Attack(0, 0, Kind.RANGED, 1, 100, 0, 0) : Attack.PLAIN);
        for (int job : jobsUpTo(world.job())) {
            for (SkillBook.Skill skill : SkillBook.forJob(job)) {
                int level = world.skillLevel(skill.id());
                SkillBook.levelOf(skill.id(), level).ifPresent(at -> {
                    if (!at.attacks() || at.mpCost() <= 0 || NOT_FOR_US.contains(skill.id())
                            || at.mpCost() > mp - reserve) {
                        return;     // passive, not an attack, or more than it can spare
                    }
                    Kind kind = kindOf(world.job(), at);
                    if (kind == Kind.RANGED && !ammunition) {
                        return;
                    }
                    int lines = Math.max(at.lines(), at.bullets());
                    options.add(new Attack(skill.id(), level, kind, lines,
                            kind == Kind.MAGIC ? 0 : at.damage(), at.magic(), at.mpCost()));
                });
            }
        }
        return options.stream().max(Comparator.comparingDouble(Fighting::worth)).orElse(Attack.PLAIN);
    }

    /** Roughly how much harm an attack does, for choosing between them. */
    private static double worth(Attack attack) {
        // A spell's magic attack is on a different scale from a percentage; about a tenth of a
        // plain hit per point is where Magic Claw at level one sits against a wand swing.
        double perLine = attack.kind() == Kind.MAGIC ? attack.magic() * 10.0 : attack.damagePercent();
        return perLine * attack.lines();
    }

    /** How far off it can start hitting, given the attack it would make. */
    public static int reach(WorldModel world, int meleeReach) {
        Attack attack = choose(world);
        return attack.kind() == Kind.MELEE ? meleeReach : RANGED_REACH;
    }

    /** Whether a target at this offset can be hit with this attack from where it stands. */
    public static boolean canHit(Attack attack, java.awt.Point self, java.awt.Point target, int meleeReach) {
        if (attack.kind() == Kind.MELEE) {
            return self.distance(target) <= meleeReach;
        }
        return Math.abs(target.x - self.x) <= RANGED_REACH && Math.abs(target.y - self.y) <= RANGED_HEIGHT;
    }

    /** The buffs it has learned and could cast now, worth keeping up. */
    public static List<SkillBook.SkillLevel> buffs(WorldModel world) {
        int mp = Math.max(0, world.stat("MP"));
        List<SkillBook.SkillLevel> buffs = new ArrayList<>();
        for (int job : jobsUpTo(world.job())) {
            for (SkillBook.Skill skill : SkillBook.forJob(job)) {
                if (NOT_FOR_US.contains(skill.id())) {
                    continue;
                }
                SkillBook.levelOf(skill.id(), world.skillLevel(skill.id())).ifPresent(at -> {
                    if (at.isBuff() && at.mpCost() > 0 && at.mpCost() <= mp) {
                        buffs.add(at);
                    }
                });
            }
        }
        return buffs;
    }

    /** How a skill is delivered: a spell, something fired, or a swing. */
    static Kind kindOf(int job, SkillBook.SkillLevel skill) {
        int branch = (job % 1000) / 100;
        if (skill.magic() > 0) {
            return Kind.MAGIC;
        }
        if (branch == 3 || skill.bullets() > 0) {
            return Kind.RANGED;     // a Bowman's every attack skill fires; a Thief's Lucky Seven throws
        }
        return Kind.MELEE;
    }

    /** A job and the ones before it: 310 is a Hunter, who also has a Bowman's skills, and a Beginner's. */
    static List<Integer> jobsUpTo(int job) {
        List<Integer> jobs = new ArrayList<>();
        if (job <= 0) {
            return List.of(0);
        }
        jobs.add(0);
        int first = (job / 100) * 100;
        jobs.add(first);
        int second = (job / 10) * 10;
        if (second != first) {
            jobs.add(second);
        }
        if (job != second) {
            jobs.add(job);
        }
        return jobs;
    }

    static final int NONE = -1;
    static final int WAND = 37;
    static final int STAFF = 38;
    static final int BOW = 45;
    static final int CROSSBOW = 46;
    static final int CLAW = 47;
    static final int GUN = 49;

    /** The kind of weapon in hand, by the item's id, or NONE. */
    static int weaponKind(WorldModel world) {
        return world.inventory().wornAt(WEAPON_SLOT).map(item -> (item.itemId() / 10000) % 100).orElse(NONE);
    }

    private static final int WEAPON_SLOT = -11;

    /** Something to fire from this weapon in the bag: arrows for a bow, bolts, stars, bullets. */
    static boolean hasAmmunition(WorldModel world, int weapon) {
        for (Item item : world.inventory().carried(Item.USE)) {
            int id = item.itemId();
            boolean fits = switch (weapon) {
                case BOW -> id / 1000 == 2060;
                case CROSSBOW -> id / 1000 == 2061;
                case CLAW -> id / 10000 == 207;
                case GUN -> id / 10000 == 233;
                default -> false;
            };
            if (fits && item.quantity() > 0) {
                return true;
            }
        }
        return false;
    }

    /** The first of these buffs that has worn off, or was never cast. */
    public static Optional<SkillBook.SkillLevel> due(List<SkillBook.SkillLevel> buffs,
                                              java.util.Map<Integer, Long> lastsUntil, long now) {
        return buffs.stream()
                .filter(buff -> lastsUntil.getOrDefault(buff.skillId(), 0L) <= now)
                .findFirst();
    }
}
