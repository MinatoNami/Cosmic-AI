package agents.world;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
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

    /** The skills learnt with this job's points, in the order the book lists them. */
    public static synchronized List<Skill> forJob(int job) {
        return BOOKS.computeIfAbsent(job, SkillBook::load);
    }

    private static List<Skill> load(int job) {
        DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.SKILL);
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
