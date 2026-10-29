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

    /** Spinel's way home from Zipangu is a list; the option that goes is the one to pick. */
    @Test
    void picksTheOptionThatGoes() {
        assertEquals(0, Agent.optionThatGoes("How's the traveling? Are you enjoying it?#b\r\n#L0#Yes, I'm done with "
                + "traveling. Can I go back to #m104000000#?\r\n#L1#No, I'd like to continue exploring this place."));
        assertEquals(-1, Agent.optionThatGoes("#L0#Tell me about this place#l\r\n#L1#Never mind#l"));
        assertEquals(2, Agent.optionThatGoes("Where to?\r\n#L0##m104000000# (100 mesos)#l\r\n#L1##m102000000# "
                + "(100 mesos)#l\r\n#L2##m101000000# (80 mesos)#l", "map:101000000"), "a taxi's list is towns");
    }

    /** Orbis's usher and ticket booth name each boat by the town it heads for. */
    @Test
    void picksThePlatformAndTheTicketByTheTown() {
        assertEquals(0, Agent.optionNaming("Which platform will you take?\r\n\r\n#L0##bThe platform to the ship "
                + "that heads to Ellinia.#l\r\n#L1##bThe platform to the train that heads to Ludibrium.#l", "Ellinia"));
        assertEquals(1, Agent.optionNaming("Which ticket would you like to purchase?\r\n#L0##bLudibrium#k#l"
                + "\r\n#L1##bEllinia of Victoria Island#k#l", "Ellinia"));
        assertEquals(-1, Agent.optionNaming("#L0##bLudibrium#k#l", "Ellinia"));
        assertEquals(-1, Agent.optionNaming("#L0##bEllinia#k#l", null));
    }

    /** The second job: past the explanations to the choosing, then the branch its weapon suits. */
    @Test
    void choosesTheSecondJobItsWeaponSuits() {
        String first = "Alright, when you have made your decision, click on [I'll choose my occupation] at the bottom.#b\r\n"
                + "#L0#Please explain to me what being the Fighter is all about.\r\n#L1#Please explain to me what being the Page is all about.\r\n"
                + "#L2#Please explain to me what being the Spearman is all about.\r\n#L3#I'll choose my occupation!";
        String second = "Now... have you made up your mind? Please choose the job you'd like to select for your 2nd job advancement. #b\r\n"
                + "#L0#Fighter\r\n#L1#Page\r\n#L2#Spearman";
        String warrior = Agent.secondJobBranch(100, 44, agents.mind.Disposition.FIGHTER);

        assertEquals("Spearman", warrior, "a polearm");
        assertEquals(3, Agent.optionForSecondJob(first, warrior));
        assertEquals(2, Agent.optionForSecondJob(second, warrior));
        assertEquals("Cleric", Agent.secondJobBranch(200, 37, agents.mind.Disposition.TALKER));
        assertEquals("Bandit", Agent.secondJobBranch(400, 33, agents.mind.Disposition.FORAGER));
    }

    /** What the job statues and instructors offer, read from their own words. */
    @Test
    void readsWhichCallingIsOnOffer() {
        assertEquals(java.util.Optional.of("magician"), Agent.callingOffered(
                "Hey #h #, I can send you to #b#m101000003##k if you want to be a #bMagician#k. Do you want to go now?"));
        assertEquals(java.util.Optional.of("magician"), Agent.callingOffered(
                "Oh...! You look like someone that can definitely be a part of us... so, what do you think? Wanna be the Magician?"));
        assertEquals(java.util.Optional.of("bowman"), Agent.callingOffered(
                "So you decided to become a #rbowman#k? There are some standards to meet, y'know..."));
        assertEquals(java.util.Optional.empty(), Agent.callingOffered("Do you want to get out of Happyville?"));
        assertEquals(java.util.Optional.of("thief"), Agent.callingOffered(
                "Oh...! You look like someone that can definitely be a part of us... all you need is a little "
                        + "sinister mind, and... yeah... so, what do you think? Wanna be the Rogue?"),
                "the Dark Lord calls it Rogue");
        assertEquals(java.util.Optional.empty(), Agent.callingOffered(
                "Once you train hard enough, you will be entitled to occupy a job. You can become a Bowman in "
                        + "Henesys, a Magician in Ellinia, a Warrior in Perion, and a Thief in Kerning City..."),
                "telling of every calling is offering none");
        assertEquals(java.util.Optional.empty(), Agent.callingOffered(
                "Level isn't the only thing that determines the advancement, though. You also need to boost up the "
                        + "levels of a particular ability based on the occupation. For example, to be a warrior, your "
                        + "STR has to be over 35, and so forth, you know what I'm saying?"),
                "advice about a calling is not an offer of one");
        assertEquals(java.util.Optional.empty(), Agent.callingOffered(
                "You want to become a #bWarrior#k? Hmmm, then I suggest you head over to #bPerion#k."),
                "sent on to somebody else is advice");
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
    /**
     * Cherry takes you onto the deck before the boat to Orbis leaves: nothing to hunt, nobody
     * there, no door. Written off as nothing to do, she was turned down for good; but the deck
     * is carried on, so it is not nowhere.
     */
    @Test
    void aRideOntoADeckThatSailsIsNotARideToNowhere() {
        agents.memory.SemanticMemory memory = new agents.memory.SemanticMemory();
        memory.assertTriple("npc:1032008", "takes_you_to", "map:101000301", 1, 1,
                agents.memory.Belief.Provenance.FIRST_HAND);
        memory.assertTriple("map:101000301", "nothing_to_do", "true", 2, 2,
                agents.memory.Belief.Provenance.INFERRED);

        assertTrue(Agent.leadsNowhereWorthGoing(1032008, memory.liveBeliefs()), "before it has sailed");

        memory.assertTriple("map:101000301", agents.world.KnownWorld.CARRIES_YOU_TO, "map:200090010", 3, 3,
                agents.memory.Belief.Provenance.FIRST_HAND);

        assertFalse(Agent.leadsNowhereWorthGoing(1032008, memory.liveBeliefs()), "the deck sails");
    }
    /** Joel sells the ship ride to Orbis, Cherry takes the boat; Heena only sends you out. */
    @Test
    void hearsAnOfferOfPassage() {
        assertTrue(Agent.offersPassage("Hello, I'm in charge of selling tickets for the ship ride to Orbis Station of Ossyria."));
        assertTrue(Agent.offersPassage("Do you want to go to Orbis?"));
        assertTrue(Agent.offersPassage("Make sure you got a Orbis ticket to travel in this boat."));
        assertFalse(Agent.offersPassage("Are you done with your training? I will send you out."));
        assertFalse(Agent.offersPassage("Zzzzzz..."));
    }

    /** The newest of what it learned the hard way, ahead of the snails it has seen. */
    @Test
    void lessonsAreToldNewestFirstAndSightingsAreNot() {
        java.util.List<agents.memory.Belief> beliefs = java.util.List.of(
                lesson(1, "map:105040302", agents.world.Yields.EARNS_PER_MINUTE, "51:4143", 10),
                lesson(2, "monster:100100", "present_in", "map:10000", 99),
                lesson(3, "map:101000301", agents.world.KnownWorld.CARRIES_YOU_TO, "map:200090010", 50));

        java.util.List<agents.memory.Belief> told = Agent.lessonsWorthTelling(beliefs);

        assertEquals(java.util.List.of(3L, 1L), told.stream().map(agents.memory.Belief::id).toList());
    }

    private static agents.memory.Belief lesson(long id, String s, String p, String o, long lastSeen) {
        return new agents.memory.Belief(id, s, p, o, 0.9, agents.memory.Belief.Provenance.FIRST_HAND,
                java.util.List.of(1L), 0, lastSeen, null, null);
    }
}
