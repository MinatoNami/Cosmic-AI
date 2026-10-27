package agents.mind;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the third job starts, which nothing in the world says.
 *
 * <p>The second job is a trail of things said: the trainer names the instructor, the
 * instructor names the test. The third has no first link. At level 70 the second-job
 * instructors say only "You have chosen wisely", and the five chiefs who start it stand
 * together in the Chief's Residence in El Nath, on another continent. A player is pointed
 * there by the quest lightbulb; an agent is told here, as {@link Training} is told the stat
 * each job statue asks for. Agent3 reached level 84 as a Cleric with every Cleric skill
 * maxed and twenty skill points it had nowhere to put, never having been to El Nath.
 *
 * <p>Everything after the first link is said again by the people in it, and followed the
 * way the second job is followed.
 */
public final class ThirdJob {

    private ThirdJob() {
    }

    /** The level the chiefs start the test at. */
    public static final int FROM_LEVEL = 70;

    /** El Nath: Chief's Residence, where all five stand. */
    public static final String CHIEFS_ROOM = "map:211000001";

    /** Each job family's chief: Tylus, Robeira, Rene, Arec and Pedro. */
    private static final Map<Integer, Integer> CHIEFS = Map.of(
            1, 2020008,
            2, 2020009,
            3, 2020010,
            4, 2020011,
            5, 2020013);

    /**
     * The chief who starts this job's third job, or -1 when it has none to start: not a
     * second job, or a family outside the five - Cygnus Knights and Aran train elsewhere.
     */
    public static int chiefFor(int job) {
        if (job <= 0 || job >= 1000 || job % 100 == 0 || job % 10 != 0) {
            return -1;
        }
        return CHIEFS.getOrDefault(job / 100, -1);
    }

    /** Whether an agent of this job and level is due its third job. */
    public static boolean due(int job, int level) {
        return level >= FROM_LEVEL && chiefFor(job) > 0;
    }

    private static final Pattern OPTION = Pattern.compile(
            "#L(\\d+)#(.*?)(?=#l|#L\\d+#|\\r|\\n|$)", Pattern.DOTALL);

    /**
     * The chief's "I want to make the 3th job advancement." from his list, or -1. The other
     * line is the Zakum Dungeon quest, which is a concession, not a job.
     */
    public static int optionForAdvancement(String said) {
        Matcher option = OPTION.matcher(said);
        while (option.find()) {
            if (option.group(2).toLowerCase().contains("job advancement")) {
                return Integer.parseInt(option.group(1));
            }
        }
        return -1;
    }
}
