package agents.mind;

import agents.memory.Belief;

import agents.percept.Observation;
import agents.protocol.ClientPackets;
import agents.world.SkillBook;
import agents.world.WorldModel;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ability and skill points, which nothing spent: one agent reached level 27 with 80 unused. */
class TrainingTest {

    /** A first-job warrior's book, as Skill.wz has it. */
    private static final List<SkillBook.Skill> WARRIOR = List.of(
            new SkillBook.Skill(1000000, 16, Map.of(), false),
            new SkillBook.Skill(1001004, 20, Map.of(), true),
            new SkillBook.Skill(1001005, 20, Map.of(1001004, 1), true));

    private static final List<SkillBook.Skill> BEGINNER = List.of(
            new SkillBook.Skill(1000, 3, Map.of(), true),
            new SkillBook.Skill(1001, 3, Map.of(), false),
            new SkillBook.Skill(1002, 3, Map.of(), false));

    private static Training training(Disposition disposition) {
        return new Training(disposition, job -> job == 100 ? WARRIOR : job == 0 ? BEGINNER : List.of());
    }

    private static WorldModel character(int job, int level, Map<String, Integer> stats) {
        WorldModel world = new WorldModel();
        world.update(new Observation.MapEntered(1, 10000, 0));
        java.util.HashMap<String, Integer> all = new java.util.HashMap<>(stats);
        all.put("JOB", job);
        all.put("LEVEL", level);
        all.putIfAbsent("HP", 100);
        all.putIfAbsent("MAXHP", 100);
        world.update(new Observation.StatsChanged(1, all));
        return world;
    }

    private static boolean is(Optional<Packet> sent, Packet expected) {
        return sent.isPresent() && Arrays.equals(sent.get().getBytes(), expected.getBytes());
    }

    @Test
    void aWarriorPutsPointsIntoStrengthOnceItsDexterityIsEnough() {
        WorldModel world = character(100, 20, Map.of("AVAILABLEAP", 10, "STR", 40, "DEX", 20));

        assertTrue(is(training(Disposition.FIGHTER).step(world), ClientPackets.distributeAp(Training.STR)));
    }

    @Test
    void itBacksUpItsMainStatWhenTheOtherFallsBehind() {
        WorldModel world = character(100, 30, Map.of("AVAILABLEAP", 10, "STR", 60, "DEX", 6));

        assertTrue(is(training(Disposition.FIGHTER).step(world), ClientPackets.distributeAp(Training.DEX)),
                "DEX 6 at level 30 is short of 4 + 30/2");
    }

    @Test
    void aYoungBeginnerLeavesItsPointsToTheServer() {
        WorldModel world = character(0, 9, Map.of("AVAILABLEAP", 5, "STR", 20, "DEX", 5));

        assertTrue(training(Disposition.FIGHTER).step(world).isEmpty() || !is(training(Disposition.FIGHTER).step(world),
                ClientPackets.distributeAp(Training.STR)), "the server places a beginner's first ten levels");
    }

    /** Agent1's case: a beginner at 27 with 80 points and nowhere they went. */
    @Test
    void anOlderBeginnerSpendsByTemperament() {
        WorldModel world = character(0, 27, Map.of("AVAILABLEAP", 80, "STR", 57, "DEX", 20));

        assertTrue(is(training(Disposition.FIGHTER).step(world), ClientPackets.distributeAp(Training.STR)));
    }

    @Test
    void skillPointsGoToWhatHitsFirstAndRespectWhatComesBefore() {
        WorldModel world = character(100, 12, Map.of("AVAILABLESP", 3, "AVAILABLEAP", 0));
        Training training = training(Disposition.FIGHTER);

        assertTrue(is(training.step(world), ClientPackets.distributeSp(1001004)),
                "Power Strike deals damage and needs nothing first");

        world.update(new Observation.SkillChanged(2, 1001004, 1, 0));
        for (int i = 0; i < Training.BETWEEN_POINTS; i++) {
            training.step(world);
        }
        Optional<Packet> next = training.step(world);
        assertTrue(is(next, ClientPackets.distributeSp(1001004)) || is(next, ClientPackets.distributeSp(1001005)),
                "another damage skill point, now that Slash Blast's prerequisite is met");
    }

    @Test
    void aPointTheServerIgnoresIsNotAskedForEveryStep() {
        WorldModel world = character(100, 12, Map.of("AVAILABLESP", 3, "AVAILABLEAP", 0));
        Training training = training(Disposition.FIGHTER);

        int asked = 0;
        for (int i = 0; i < 60; i++) {
            if (is(training.step(world), ClientPackets.distributeSp(1001004))) {
                asked++;
            }
        }

        assertEquals(1, asked, "asked for Power Strike " + asked + " times with nothing coming back");
    }

    @Test
    void aBeginnerHasOnePointALevelUpToSix() {
        WorldModel world = character(0, 3, Map.of("AVAILABLEAP", 0));
        world.update(new Observation.SkillChanged(2, 1000, 2, 0));

        assertTrue(training(Disposition.FIGHTER).step(world).isEmpty(), "two points at level three, both spent");
    }

    private static Belief taught(String subject, String predicate, String object, double confidence) {
        return new Belief(0, subject, predicate, object, confidence, Belief.Provenance.HEARSAY,
                List.of(0L), 0, 0, null, null);
    }

    /** Somebody played a warrior with as much DEX as STR; the valuation alone would not. */
    @Test
    void pointsGoWherePeopleOfTheJobPutThem() {
        List<Belief> shown = List.of(
                taught("job:100", "ap_share_str", "50", 0.5),
                taught("job:100", "ap_share_dex", "50", 0.5));
        Training training = new Training(Disposition.FIGHTER, job -> List.of(), () -> shown);
        WorldModel world = character(100, 20, Map.of("AVAILABLEAP", 10, "STR", 40, "DEX", 20));

        assertTrue(is(training.step(world), ClientPackets.distributeAp(Training.DEX)),
                "DEX 20 against STR 40 is the biggest gap from half and half");
    }

    @Test
    void sharesShownForAnotherJobAreNotThisOnes() {
        List<Belief> shown = List.of(taught("job:200", "ap_share_int", "100", 0.5));
        Training training = new Training(Disposition.FIGHTER, job -> List.of(), () -> shown);
        WorldModel world = character(100, 20, Map.of("AVAILABLEAP", 10, "STR", 40, "DEX", 20));

        assertTrue(is(training.step(world), ClientPackets.distributeAp(Training.STR)));
    }

    @Test
    void skillsPeopleChoseComeFirst() {
        List<Belief> shown = List.of(taught("job:100", "puts_sp_into", "skill:1000000", 0.5));
        Training training = new Training(Disposition.FIGHTER,
                job -> job == 100 ? WARRIOR : List.of(), () -> shown);
        WorldModel world = character(100, 12, Map.of("AVAILABLESP", 3));

        assertTrue(is(training.step(world), ClientPackets.distributeSp(1000000)),
                "the HP-recovery skill people chose, ahead of the damage skill the valuation prefers");
    }
}
