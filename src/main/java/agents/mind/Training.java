package agents.mind;

import agents.protocol.ClientPackets;
import agents.world.SkillBook;
import agents.world.WorldModel;
import net.packet.Packet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Spends ability points and skill points, one at a time.
 *
 * <p>Nothing spent them. The server hands a character five ability points a level and, after a
 * job, three skill points; one agent reached level 27 carrying 80 ability points it had never
 * used, so every level it gained made it no stronger.
 *
 * <p>Like {@link Wardrobe} and {@link Survival}, the valuation is given rather than learnt:
 * each job has a stat it lives on and one that backs it up, which is the first thing a new
 * player is told, and damage skills come before the rest. What it is applied to is the
 * agent's own: the points the server says it has, the job it has taken, and the skills its
 * skill window lists.
 *
 * <p>A point the server declines - a skill already at its limit after a restart, a
 * prerequisite not met - is set aside rather than asked for again every step.
 */
public final class Training {

    /** Stat bits, as the server names them. */
    static final int STR = 0x40;
    static final int DEX = 0x80;
    static final int INT = 0x100;
    static final int LUK = 0x200;

    /** Steps between points. The server ignores requests that come too close together. */
    static final int BETWEEN_POINTS = 3;

    /** Steps to wait for the server to show a point landed before deciding it will not. */
    static final int PATIENCE = 6;

    /** How long a declined skill is left alone before it is worth trying again. */
    static final int DECLINED_FOR = 600;

    /** Beginners only ever get six skill points, one a level from level two. */
    private static final int BEGINNER_SKILL_POINTS = 6;

    private final Disposition disposition;
    private final IntFunction<List<SkillBook.Skill>> books;
    private int cooldown;

    private Integer skillPending;
    private int pendingLevel;
    private int waited;
    private final Map<Integer, Integer> declinedAt = new HashMap<>();
    private int apPending = -1;
    private int apWaited;
    /** When the server last ignored an ability point, or -1 for never. */
    private int apDeclinedAt = -1;
    private int steps;

    public Training(Disposition disposition) {
        this(disposition, SkillBook::forJob);
    }

    /** For tests, which cannot load Skill.wz. */
    Training(Disposition disposition, IntFunction<List<SkillBook.Skill>> books) {
        this.disposition = disposition;
        this.books = books;
    }

    /** The one point worth spending now, if any. */
    public Optional<Packet> step(WorldModel world) {
        steps++;
        if (world.isDead() || world.level() < 1 || world.job() < 0) {
            return Optional.empty();
        }
        watchForTheServer(world);
        if (cooldown > 0) {
            cooldown--;
            return Optional.empty();
        }
        Optional<Packet> point = abilityPoint(world).or(() -> skillPoint(world));
        point.ifPresent(p -> cooldown = BETWEEN_POINTS);
        return point;
    }

    /** Notices whether the last point landed, and gives up waiting if it did not. */
    private void watchForTheServer(WorldModel world) {
        if (skillPending != null) {
            if (world.skillLevel(skillPending) > pendingLevel) {
                skillPending = null;
            } else if (++waited > PATIENCE) {
                declinedAt.put(skillPending, steps);
                skillPending = null;
            }
        }
        if (apPending >= 0) {
            if (world.stat("AVAILABLEAP") < apPending) {
                apPending = -1;
            } else if (++apWaited > PATIENCE) {
                apDeclinedAt = steps;
                apPending = -1;
            }
        }
    }

    private Optional<Packet> abilityPoint(WorldModel world) {
        int available = world.stat("AVAILABLEAP");
        boolean recentlyDeclined = apDeclinedAt >= 0 && steps - apDeclinedAt < DECLINED_FOR;
        if (available <= 0 || apPending >= 0 || recentlyDeclined) {
            return Optional.empty();
        }
        // A beginner of level ten or under has its points placed by the server, and a request
        // of its own is refused.
        if (world.job() == 0 && world.level() <= 10) {
            return Optional.empty();
        }
        apPending = available;
        apWaited = 0;
        return Optional.of(ClientPackets.distributeAp(statToRaise(world)));
    }

    /**
     * The stat this point goes into: the one the job lives on, except while the one backing
     * it up is short of half the character's level.
     */
    int statToRaise(WorldModel world) {
        int[] pair = stats(world.job());
        int secondary = world.stat(name(pair[1]));
        int wanted = 4 + world.level() / 2;
        return secondary >= 0 && secondary < wanted ? pair[1] : pair[0];
    }

    /** Main stat and backing stat for a job, or for a beginner, for the kind of agent it is. */
    int[] stats(int job) {
        int family = (job % 1000) / 100;
        return switch (family) {
            case 1 -> new int[]{STR, DEX};
            case 2 -> new int[]{INT, LUK};
            case 3 -> new int[]{DEX, STR};
            case 4 -> new int[]{LUK, DEX};
            default -> beginnerLeaning();
        };
    }

    /**
     * A beginner has no job to say what matters, so its temperament does: whoever likes a
     * fight grows strong, whoever keeps moving grows quick, whoever talks grows clever.
     */
    private int[] beginnerLeaning() {
        if (disposition.aggression() >= 0.6) {
            return new int[]{STR, DEX};
        }
        if (disposition.curiosity() >= 0.6 || disposition.sociability() >= 0.8) {
            return new int[]{INT, LUK};
        }
        if (disposition.greed() >= 0.6) {
            return new int[]{LUK, DEX};
        }
        return new int[]{DEX, STR};
    }

    private static String name(int stat) {
        return switch (stat) {
            case STR -> "STR";
            case DEX -> "DEX";
            case INT -> "INT";
            default -> "LUK";
        };
    }

    private Optional<Packet> skillPoint(WorldModel world) {
        if (skillPending != null) {
            return Optional.empty();
        }
        List<SkillBook.Skill> book = books.apply(world.job());
        if (book.isEmpty() || !hasSkillPoints(world, book)) {
            return Optional.empty();
        }
        List<SkillBook.Skill> order = new ArrayList<>(book);
        // Damage first, because a point in a skill that hits harder is one the agent feels at
        // once; otherwise as the window lists them, which keeps prerequisites early.
        order.sort(Comparator.comparing((SkillBook.Skill s) -> !s.dealsDamage()));
        for (SkillBook.Skill skill : order) {
            int level = world.skillLevel(skill.id());
            Integer declined = declinedAt.get(skill.id());
            if (level >= skill.maxLevel() || (declined != null && steps - declined < DECLINED_FOR)) {
                continue;
            }
            boolean ready = skill.requires().entrySet().stream()
                    .allMatch(req -> world.skillLevel(req.getKey()) >= req.getValue());
            if (!ready) {
                continue;
            }
            skillPending = skill.id();
            pendingLevel = level;
            waited = 0;
            return Optional.of(ClientPackets.distributeSp(skill.id()));
        }
        return Optional.empty();
    }

    /**
     * A job's points are a stat the server reports. A beginner's are not: it has one a level
     * from level two, up to six, and what is left is what its three skills do not account for.
     */
    private boolean hasSkillPoints(WorldModel world, List<SkillBook.Skill> book) {
        if (world.job() != 0) {
            return world.stat("AVAILABLESP") > 0;
        }
        int spent = book.stream().mapToInt(s -> world.skillLevel(s.id())).sum();
        return Math.min(world.level() - 1, BEGINNER_SKILL_POINTS) - spent > 0;
    }
}
