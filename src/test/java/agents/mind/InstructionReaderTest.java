package agents.mind;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a finished conversation for what was asked, on Roger's words as the server sends them.
 */
class InstructionReaderTest {

    private static final List<String> ROGER = List.of(
            "So..... Let me just do this for fun! Abaracadabra~!",
            "Surprised? If HP becomes 0, then you are in trouble. Now, I will give you #rRoger's Apple#k. "
                    + "Please take it. You will feel stronger. Open the Item window and double click to consume.",
            "Please take all Roger's Apples that I gave you. You will be able to see the HP bar increasing. "
                    + "Please talk to me again when you recover your HP 100%.");

    private static InstructionReader.Conversation roger() {
        return new InstructionReader.Conversation(2000, ROGER, Map.of(2010007, 1),
                Map.of(2010007, 1, 2000000, 5), Set.of(2010007), List.of(1021), 25, 50);
    }

    private record Answers(String answer) implements Oracle {
        @Override
        public String ask(String system, String user) {
            return answer;
        }

        @Override
        public String name() {
            return "fixed";
        }
    }

    @Test
    void eatsWhatRogerGaveIt() {
        List<InstructionReader.Step> steps = new InstructionReader(
                new Answers("{\"steps\":[{\"do\":\"USE_ITEM\",\"id\":2010007,\"count\":1}]}")).read(roger());

        assertEquals(List.of(new InstructionReader.Step(InstructionReader.Kind.USE_ITEM, 2010007, 1)), steps);
    }

    /** A model that invents ids gets those steps dropped, not an agent sent after nothing. */
    @Test
    void dropsStepsAboutThingsItCannotSee() {
        List<InstructionReader.Step> steps = new InstructionReader(new Answers("""
                Sure! {"steps":[{"do":"USE_ITEM","id":2010999,"count":1},
                                {"do":"TALK_TO","id":1012100,"count":1},
                                {"do":"GO_TO_MAP","id":100000000,"count":1},
                                {"do":"USE_ITEM","id":2010007,"count":3}]}""")).read(roger());

        assertEquals(List.of(new InstructionReader.Step(InstructionReader.Kind.USE_ITEM, 2010007, 3)), steps,
                "nothing named 1012100 or 100000000 was said, and 2010999 is not in the bag");
    }

    @Test
    void keepsPeopleAndPlacesTheConversationNamed() {
        InstructionReader.Conversation letter = new InstructionReader.Conversation(1012100,
                List.of("Take this to #p1072000# who's around #m102020300#."), Map.of(4031008, 1),
                Map.of(), Set.of(), List.of(), 50, 50);
        List<InstructionReader.Step> steps = new InstructionReader(new Answers(
                "{\"steps\":[{\"do\":\"GO_TO_MAP\",\"id\":102020300,\"count\":1},"
                        + "{\"do\":\"TALK_TO\",\"id\":1072000,\"count\":1}]}")).read(letter);

        assertEquals(2, steps.size());
    }

    @Test
    void anUnreachableModelAsksForNothing() {
        assertTrue(new InstructionReader(new Answers(null)).read(roger()).isEmpty());
    }

    @Test
    void tellsTheModelWhatItWasJustGiven() {
        String told = InstructionReader.describe(roger());

        assertTrue(told.contains("You were given, during this conversation: item:2010007 x1 (restores HP)"), told);
        assertTrue(told.contains("You have 25 of 50 HP"), told);
    }
}
