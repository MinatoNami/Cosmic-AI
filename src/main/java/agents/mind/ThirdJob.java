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
 * way the second job is followed - except where the clone is, which is told here too.
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
     * Where each family's Door of Dimension stands, which opens on the instructor's clone.
     *
     * <p>The second link the people in it do not say either. Grendel's "an opening in the
     * middle of a deep forest of evil in Victoria Island" names no map and nobody, so nothing
     * could be written down from it: Agent3, a level-85 Cleric, spoke to him, took that for the
     * errand done, and went back and forth between Lith Harbor and the Nautilus for a day.
     */
    private static final Map<Integer, String> DOORS = Map.of(
            1, "map:105070001",     // Ant Tunnel Park
            2, "map:100040106",     // The Forest of Evil II
            3, "map:105040305",     // Sleepy Dungeon V
            4, "map:107000402",     // Monkey Swamp II
            5, "map:105070200");    // The Cave of Evil Eye II

    /** Door of Dimension: talked to with the test under way, it lets the agent in to the clone. */
    public static final int DOOR = 1061009;

    /** Black Charm, which the clone drops, for the instructor. */
    public static final int CHARM = 4031059;

    /** The Necklace of Strength, which the instructor gives for the charm, for the chief. */
    public static final int NECKLACE = 4031057;

    /** The rooms the doors open on, lobby and clone, for all five families. */
    private static final int CLONE_ROOMS_FROM = 108010100;
    private static final int CLONE_ROOMS_TO = 108010501;

    /** This job's Door of Dimension, or null when it has no third job to go for. */
    public static String doorFor(int job) {
        return chiefFor(job) > 0 ? DOORS.get(job / 100) : null;
    }

    /** Whether this is one of the rooms behind a Door of Dimension, where the clone is. */
    public static boolean isClonesRoom(int mapId) {
        return mapId >= CLONE_ROOMS_FROM && mapId <= CLONE_ROOMS_TO && mapId % 100 <= 1;
    }

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
