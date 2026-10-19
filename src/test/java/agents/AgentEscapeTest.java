package agents;

import agents.memory.Belief;
import agents.memory.SemanticMemory;
import agents.world.KnownWorld;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting out of a room whose only door will not open.
 *
 * The Nautilus' cow stable, map 912000100, has one way out: ntq2, a scripted door that lets
 * nobody out in the middle of fetching milk until the jug is full. Agent1 walked into it 489
 * times, wrote it off as going nowhere, and spent the rest of the day there with a return
 * scroll in its bag, because the scroll was read only when no door could be walked to.
 */
class AgentEscapeTest {

    private static final int STABLE = 912000100;

    @Test
    void aRoomWhoseOnlyDoorGoesNowhereIsADeadEnd() {
        SemanticMemory memory = new SemanticMemory();
        memory.assertTriple(KnownWorld.portalRef(STABLE, "ntq2"), "leads_to", KnownWorld.NOWHERE, 1, 10,
                Belief.Provenance.INFERRED);

        assertTrue(Agent.everyDoorGoesNowhere(STABLE, Set.of("ntq2"), memory.liveBeliefs()));
    }

    @Test
    void aDoorNeverTriedIsAWayOut() {
        SemanticMemory memory = new SemanticMemory();
        memory.assertTriple(KnownWorld.portalRef(STABLE, "ntq2"), "leads_to", KnownWorld.NOWHERE, 1, 10,
                Belief.Provenance.INFERRED);

        assertFalse(Agent.everyDoorGoesNowhere(STABLE, Set.of("ntq2", "out00"), memory.liveBeliefs()),
                "out00 has never been tried");
        assertFalse(Agent.everyDoorGoesNowhere(STABLE, Set.of(), memory.liveBeliefs()),
                "no door in reach is the other kind of walled in");
    }

    @Test
    void aDoorThatHasWorkedIsAWayOut() {
        SemanticMemory memory = new SemanticMemory();
        memory.assertTriple(KnownWorld.portalRef(STABLE, "ntq2"), "leads_to", "map:120000103", 1, 10,
                Belief.Provenance.FIRST_HAND);

        assertFalse(Agent.everyDoorGoesNowhere(STABLE, Set.of("ntq2"), memory.liveBeliefs()));
    }

    /**
     * What is under way is what moved since it came in, by when it first believed it was here:
     * Agent1 was restarted inside the stable, and the milk quest last changed before that.
     */
    @Test
    void theQuestsUnderWayAreTheOnesThatMovedSinceItCameIn() {
        SemanticMemory memory = new SemanticMemory();
        memory.assertTriple("quest:1000", "state", "1", 1, 50, Belief.Provenance.FIRST_HAND);
        memory.assertTriple("self", "in_map", KnownWorld.mapRef(STABLE), 2, 100, Belief.Provenance.FIRST_HAND);
        memory.assertTriple("quest:2180", "state", "1", 3, 150, Belief.Provenance.FIRST_HAND);
        memory.assertTriple("quest:2179", "state", "2", 4, 160, Belief.Provenance.FIRST_HAND);
        // Restarted: still there, the same belief corroborated, first seen when it came in.
        memory.assertTriple("self", "in_map", KnownWorld.mapRef(STABLE), 5, 900, Belief.Provenance.FIRST_HAND);

        List<Integer> underWay = Agent.questsUnderWayIn(STABLE, memory.liveBeliefs());

        assertEquals(List.of(2180), underWay, "1000 was taken before, 2179 is finished");
        assertEquals(List.of(), Agent.questsUnderWayIn(120000000, memory.liveBeliefs()), "not where it is");
    }
}
