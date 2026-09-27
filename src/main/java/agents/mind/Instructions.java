package agents.mind;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an NPC's words name, read from the markup the client would draw as names.
 *
 * <p>An NPC saying "get this letter to #p1072000# who's around #m102020300#" is shown to a
 * player as a person and a place. The agent is shown the ids, which is the same information in
 * the form its memory keeps: who, where, and - from "collect 30 #t4031013#" - what and how
 * many. Being told where to go by somebody talking to you is perception, like the rest of the
 * conversation; nothing here reads a name the agent was not given.
 *
 * <p>This is what the second job hangs on. Every step of it is an instruction of this shape,
 * and an agent that only got the gist from a model's summary lost the ids that let it act.
 */
public final class Instructions {

    private Instructions() {
    }

    /** A number of an item somebody asked for. */
    public record ItemAsked(int quantity, int itemId) {
    }

    /** Everything named in one thing said. */
    public record Heard(List<ItemAsked> items, List<Integer> maps, List<Integer> npcs) {

        public boolean isEmpty() {
            return items.isEmpty() && maps.isEmpty() && npcs.isEmpty();
        }
    }

    /** "30 #t4031013#", with whatever colour markup sits between. */
    private static final Pattern ITEM = Pattern.compile("(\\d+)\\s*(?:#[bkrdegn])*\\s*#t(\\d+)#");
    private static final Pattern MAP = Pattern.compile("#m(\\d+)#");
    private static final Pattern NPC = Pattern.compile("#p(\\d+)#");

    public static Heard read(String said) {
        List<ItemAsked> items = new ArrayList<>();
        Matcher item = ITEM.matcher(said);
        while (item.find()) {
            items.add(new ItemAsked(Integer.parseInt(item.group(1)), Integer.parseInt(item.group(2))));
        }
        return new Heard(items, ids(MAP, said), ids(NPC, said));
    }

    private static List<Integer> ids(Pattern pattern, String said) {
        List<Integer> ids = new ArrayList<>();
        Matcher m = pattern.matcher(said);
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
