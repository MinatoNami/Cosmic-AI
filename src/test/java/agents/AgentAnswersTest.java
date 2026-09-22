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
    void declinesAQuestionItCannotRead() {
        assertEquals(NO, Agent.withoutReading(1), "style 1 is sendYesNo");
        assertEquals(NO, Agent.withoutReading(0x0C), "style 12 is sendAcceptDecline");
    }

    /**
     * A statement still needs acknowledging, or the conversation sits open forever with
     * nobody attending it and the agent never gets another decision out of that NPC.
     */
    @Test
    void acknowledgesAStatement() {
        assertEquals(YES_OR_NEXT, Agent.withoutReading(0), "style 0 is sendNext");
        assertEquals(YES_OR_NEXT, Agent.withoutReading(4), "a menu is not a yes-or-no");
    }
}
