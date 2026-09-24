package agents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(YES_OR_NEXT, Agent.withoutReading(4, false), "a menu is not a yes-or-no");
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
}
