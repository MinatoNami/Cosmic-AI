package agents;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an agent answers when nobody read the question.
 *
 * Saying yes to everything is how one left Maple Island at level three: NPC 2007 stands a
 * few steps from where every character starts and asks "would you like to skip the tutorials
 * and head straight to Lith Harbor?". Another NPC warped one into an empty tutorial room
 * with no portals, no people and nothing to climb, where it wandered until it was noticed.
 * Both were offers, and both were accepted by something with no way of reading them.
 */
class AgentAnswersTest {

    private static final byte YES_OR_NEXT = 1;
    private static final byte NO = 0;

    @Test
    void declinesAQuestionItCannotReadWhileItStillHasSomewhereToGo() {
        assertEquals(NO, Agent.withoutReading(1, false), "style 1 is sendYesNo");
        assertEquals(NO, Agent.withoutReading(0x0C, false), "style 12 is sendAcceptDecline");
    }

    /**
     * The same question, asked of an agent that has run out of world.
     *
     * This is the distinction the reflex could not previously draw. NPC 2007 offers to skip
     * the tutorials on the first morning, with an entire island unexplored - refuse. Shanks
     * offers passage off that island once every door on it has been opened - accept, because
     * refusing leaves the agent with nothing to do for the rest of its life.
     */
    @Test
    void acceptsAnOfferToBeTakenSomewhereWhenThereIsNowhereLeft() {
        assertEquals(YES_OR_NEXT, Agent.withoutReading(1, true));
        assertEquals(YES_OR_NEXT, Agent.withoutReading(0x0C, true));
    }

    /**
     * A statement still needs acknowledging either way, or the conversation sits open with
     * nobody attending it and the agent never gets another decision out of that NPC.
     */
    @Test
    void acknowledgesAStatement() {
        assertEquals(YES_OR_NEXT, Agent.withoutReading(0, false), "style 0 is sendNext");
        assertEquals(YES_OR_NEXT, Agent.withoutReading(0, true));
    }

    /**
     * A list wants one of its options or nothing, and "next" with none chosen leaves the
     * script waiting forever - and the server deaf to every greeting after it. Unread, a
     * list is walked away from; choosing blind could mean paying for something.
     */
    @Test
    void walksAwayFromAListItCannotRead() {
        assertEquals(NO, Agent.withoutReading(4, false), "style 4 is sendSimple");
        assertEquals(NO, Agent.withoutReading(4, true), "even with nowhere left to go");
    }

    /**
     * The condition is "nowhere left to go", and somebody here you have not met is
     * somewhere left to go.
     *
     * Both agents were repeatedly carried out of Southperry - the one map holding the person
     * who sells passage off the island - by whoever offered them a lift first, and one of
     * those lifts put an agent in a tutorial room with no doors. Twice, into two different
     * rooms. What it was looking for was standing in the map it kept agreeing to leave.
     *
     * This test states the rule the acceptance now follows; the visibility check that feeds
     * it lives in the agent, where the world model is.
     */
    @Test
    void theRuleIsAboutHavingNowhereLeftRatherThanNothingToDo() {
        assertEquals(NO, Agent.withoutReading(1, false),
                "somewhere left to go, including somebody here still unmet");
        assertEquals(YES_OR_NEXT, Agent.withoutReading(1, true),
                "genuinely nothing left: no unopened door, nobody here unmet");
    }

    /**
     * Heena asks "are you done with your training?" before she has offered anything, and a yes
     * sends the agent off the island's first map with her quests and Sera's never taken.
     */
    @Test
    void staysWhileThereIsStillSomethingToDoHere() {
        Set<Integer> heenaAndSera = Set.of(2101, 2100);

        assertTrue(Agent.businessHereFirst(2101, Map.of(), heenaAndSera),
                "she has quests on offer it has never taken");
        assertTrue(Agent.businessHereFirst(2101, Map.of(1000, "1", 1031, "1"), heenaAndSera),
                "taken, but Sera, in sight, is still waiting to finish them");
        assertFalse(Agent.businessHereFirst(2101, Map.of(1000, "2", 1031, "2", 1001, "2"), heenaAndSera),
                "all done: being sent on is the way on");
    }

    /**
     * Shanks offers a quest nobody on Maple Island could start, and quest 1039 stays under way
     * because nobody could finish it. Both kept every agent turning the ferry down.
     */
    @Test
    void whatWasTriedAndRefusedIsNoReasonToStay() {
        Set<Integer> heenaAndSera = Set.of(2101, 2100);

        assertFalse(Agent.businessHereFirst(2101, Map.of(1031, "1"), Set.of(1000), Set.of(1031), heenaAndSera),
                "1000 was asked for and never started; 1031 was offered back and not taken");
        assertTrue(Agent.businessHereFirst(2101, Map.of(1031, "1"), Set.of(1000), Set.of(), heenaAndSera),
                "1031 has not been offered back yet");
    }

    /**
     * Rooney offers HappyVille, where there is nothing to hunt and no way out but asking. The
     * NPC who takes people back out of it goes somewhere worth going, so is not refused.
     */
    @Test
    void turnsDownAnOfferThatGoesSomewhereWithNothingToDo() {
        agents.memory.SemanticMemory memory = new agents.memory.SemanticMemory();
        memory.assertTriple("npc:1022101", "takes_you_to", "map:209000000", 0, 1, agents.memory.Belief.Provenance.FIRST_HAND);
        memory.assertTriple("npc:2002000", "takes_you_to", "map:105040300", 1, 2, agents.memory.Belief.Provenance.FIRST_HAND);
        memory.assertTriple("map:209000000", "nothing_to_do", "true", 2, 3, agents.memory.Belief.Provenance.INFERRED);

        assertTrue(Agent.leadsNowhereWorthGoing(1022101, memory.liveBeliefs()), "Rooney's HappyVille");
        assertFalse(Agent.leadsNowhereWorthGoing(2002000, memory.liveBeliefs()), "the way home");
        assertFalse(Agent.leadsNowhereWorthGoing(22000, memory.liveBeliefs()), "never taken, so not known to be wasted");
    }

    /** What the job statues and instructors offer, read from their own words. */
    @Test
    void readsWhichCallingIsOnOffer() {
        assertEquals(java.util.Optional.of("magician"), Agent.callingOffered(
                "Hey #h #, I can send you to #b#m101000003##k if you want to be a #bMagician#k. Do you want to go now?"));
        assertEquals(java.util.Optional.of("magician"), Agent.callingOffered(
                "Oh...! You look like someone that can definitely be a part of us... so, what do you think? Wanna be the Magician?"));
        assertEquals(java.util.Optional.empty(), Agent.callingOffered("Do you want to get out of Happyville?"));
    }

    /** "Come back at level ten" written down as it was said, stats and all. */
    @Test
    void readsWhenToComeBack() {
        assertEquals(java.util.Optional.of("level 10, DEX 25"), Agent.comeBackAt(
                "If you want to be a #bBowman#k, train yourself further until you reach #blevel 10, DEX 25#k."));
        assertEquals(java.util.Optional.empty(), Agent.comeBackAt("You're much stronger now. Keep training!"));
    }

    @Test
    void aFighterIsSuitedToTheWarrior() {
        assertEquals(Set.of("warrior"), agents.mind.Disposition.FIGHTER.callingsThatSuit());
        assertTrue(agents.mind.Disposition.TALKER.callingsThatSuit().contains("magician"));
    }
}
