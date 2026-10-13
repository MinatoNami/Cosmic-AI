package agents.world;

import provider.Data;
import provider.DataProvider;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The skills a job can put points into, read from Skill.wz.
 *
 * <p>This is the skill window: every player sees the skills of their job listed there, with
 * how far each goes and what it needs first, so reading them is looking rather than being
 * told. What a skill is for is not read - only whether its levels deal damage, which the
 * window shows as a damage figure.
 */
public final class SkillBook {

    private SkillBook() {
    }

    /**
     * One skill: its id, how many levels it has, what must be learnt first and to what level,
     * and whether it deals damage.
     */
    public record Skill(int id, int maxLevel, Map<Integer, Integer> requires, boolean dealsDamage) {
    }

    private static final Map<Integer, List<Skill>> BOOKS = new HashMap<>();

    /**
     * What one level of a skill does, as the skill's own data says: what it costs, how hard it
     * hits and how many times, and for how long it lasts if it is something cast on oneself.
     *
     * @param damage      percent of a plain hit, for a physical skill; 0 if it is not one
     * @param magic       the spell's magic attack, for a spell; 0 if it is not one
     * @param bullets     arrows or stars it fires, 0 for a skill that fires none
     * @param seconds     how long it lasts, for something cast on oneself; 0 otherwise
     * @param heals       whether it heals rather than harms - which is not an attack
     */
    public record SkillLevel(int skillId, int level, int mpCost, int damage, int magic, int lines,
                             int targets, int bullets, int seconds, boolean heals) {

        public boolean attacks() {
            return (damage > 0 || magic > 0) && !heals;
        }

        public boolean isBuff() {
            return seconds > 0 && damage == 0 && magic == 0 && !heals;
        }
    }

    private static final Map<Long, java.util.Optional<SkillLevel>> LEVELS = new HashMap<>();

    /** One level of one skill, read from the skill data; empty for a skill or level it has not got. */
    public static synchronized java.util.Optional<SkillLevel> levelOf(int skillId, int level) {
        if (level <= 0) {
            return java.util.Optional.empty();
        }
        return LEVELS.computeIfAbsent(((long) skillId << 8) | level, key -> readLevel(skillId, level));
    }

    private static java.util.Optional<SkillLevel> readLevel(int skillId, int level) {
        DataProvider provider = WzData.of(WZFiles.SKILL);
        Data book = provider.getData(String.format("%03d.img", skillId / 10000));
        if (book == null) {
            return java.util.Optional.empty();
        }
        Data data = book.getChildByPath("skill/" + skillId + "/level/" + level);
        if (data == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new SkillLevel(skillId, level,
                DataTool.getInt(data.getChildByPath("mpCon"), 0),
                DataTool.getInt(data.getChildByPath("damage"), 0),
                DataTool.getInt(data.getChildByPath("mad"), 0),
                Math.max(1, DataTool.getInt(data.getChildByPath("attackCount"), 1)),
                Math.max(1, DataTool.getInt(data.getChildByPath("mobCount"), 1)),
                DataTool.getInt(data.getChildByPath("bulletCount"), 0),
                DataTool.getInt(data.getChildByPath("time"), 0),
                data.getChildByPath("hp") != null));
    }

    /** The skills learnt with this job's points, in the order the book lists them. */
    public static synchronized List<Skill> forJob(int job) {
        return BOOKS.computeIfAbsent(job, SkillBook::load);
    }

    private static List<Skill> load(int job) {
        DataProvider provider = WzData.of(WZFiles.SKILL);
        Data book = provider.getData(String.format("%03d.img", job));
        if (book == null || book.getChildByPath("skill") == null) {
            return List.of();
        }
        List<Skill> skills = new ArrayList<>();
        for (Data skill : book.getChildByPath("skill")) {
            if (DataTool.getInt(skill.getChildByPath("invisible"), 0) != 0) {
                continue;       // not in the window, so not a choice a player has
            }
            int id;
            try {
                id = Integer.parseInt(skill.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            Data levels = skill.getChildByPath("level");
            int max = 0;
            if (levels != null) {
                for (Data ignored : levels) {
                    max++;
                }
            }
            if (max == 0) {
                continue;
            }
            Map<Integer, Integer> requires = new LinkedHashMap<>();
            Data req = skill.getChildByPath("req");
            if (req != null) {
                for (Data r : req) {
                    try {
                        requires.put(Integer.parseInt(r.getName()), DataTool.getInt(r, 0));
                    } catch (NumberFormatException ignored) {
                        // not a skill id
                    }
                }
            }
            boolean damage = skill.getChildByPath("level/1/damage") != null
                    || skill.getChildByPath("level/1/mad") != null
                    || skill.getChildByPath("level/1/fixdamage") != null;
            skills.add(new Skill(id, max, Map.copyOf(requires), damage));
        }
        return List.copyOf(skills);
    }
}
