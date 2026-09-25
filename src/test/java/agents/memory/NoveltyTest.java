package agents.memory;

import agents.Mind;
import agents.percept.Observation;
import agents.trace.Trace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What counts as finding something out.
 *
 * Both failures this replaced came from counting the wrong things: a fighter's experience
 * ticking up looked like learning, and walking back into a known map looked like progress.
 */
class NoveltyTest {

    @TempDir
    Path traceDir;

    private Mind mind;

    @BeforeEach
    void setUp() {
        mind = new Mind("Test", Trace.toFile(traceDir.resolve("t.jsonl"), "Test"));
        mind.take(new Observation.MapEntered(1, 10000, 0));
    }

    @Test
    void itsOwnNumbersGoingUpAreNotNews() {
        long before = mind.novelties();

        mind.saw("self", "exp", "100", 2);
        mind.saw("self", "exp", "140", 3);
        mind.saw("self", "meso", "900", 4);

        assertEquals(before, mind.novelties());
    }

    @Test
    void aLevelIsNews() {
        long before = mind.novelties();

        mind.saw("self", "level", "8", 2);

        assertEquals(before + 1, mind.novelties());
    }

    @Test
    void aMapIsNewsOnlyTheFirstTime() {
        mind.saw("self", "in_map", "map:20000", 2);
        long afterFirstVisit = mind.novelties();

        mind.saw("self", "in_map", "map:10000", 3);
        mind.saw("self", "in_map", "map:20000", 4);
        mind.saw("self", "in_map", "map:10000", 5);

        assertEquals(afterFirstVisit, mind.novelties(),
                "going back and forth between two known maps is not finding anything out");
    }

    @Test
    void somethingSeenAboutTheWorldIsNewsAndSeeingItAgainIsNot() {
        long before = mind.novelties();

        mind.saw("npc:2005", "present_in", "map:10000", 2);
        mind.saw("npc:2005", "present_in", "map:10000", 3);

        assertEquals(before + 1, mind.novelties());
        assertEquals(2, mind.lastNoveltyTick());
    }

    @Test
    void beingToldIsNotFindingOut() {
        long before = mind.novelties();

        mind.hear("npc:2005", "present_in", "map:30000", 2);
        mind.infer("monster:100100", "hurts", "self", 3);

        assertEquals(before, mind.novelties());
    }

    /** The one conclusion that counts: a door found to lead somewhere. */
    @Test
    void workingOutWhereADoorGoesIsNews() {
        long before = mind.novelties();

        mind.infer("portal:10000/out00", "leads_to", "map:20000", 2);

        assertEquals(before + 1, mind.novelties());
    }
}
