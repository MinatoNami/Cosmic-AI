package agents.provision;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A valid starting appearance, read from {@code Etc.wz/MakeCharInfo.img} rather than
 * hardcoded.
 *
 * The server validates new characters against exactly this data, so reading it is the only
 * way to be sure a creation request will be accepted. The real client's character creation
 * screen reads the same file to populate its options, which is why doing so here is
 * provisioning rather than a peek at knowledge an agent should have to earn: it happens
 * before the agent exists, and describes its own body, not the world.
 */
public record StarterLook(int face, int hair, int hairColor, int skin,
                          int top, int bottom, int shoes, int weapon, boolean male) {

    private static final String[] FIELDS = {"0", "1", "2", "3", "4", "5", "6", "7"};

    /** Picks one valid combination at random, so a population of agents does not look identical. */
    public static StarterLook random(Random random, boolean male) {
        Data info = DataProviderFactory.getDataProvider(WZFiles.ETC)
                .getData("MakeCharInfo.img")
                .getChildByPath("Info/" + (male ? "CharMale" : "CharFemale"));

        List<List<Integer>> options = new ArrayList<>();
        for (String field : FIELDS) {
            options.add(valuesOf(info, field));
        }

        return new StarterLook(
                pick(random, options.get(0)),
                pick(random, options.get(1)),
                pick(random, options.get(2)),
                pick(random, options.get(3)),
                pick(random, options.get(4)),
                pick(random, options.get(5)),
                pick(random, options.get(6)),
                pick(random, options.get(7)),
                male);
    }

    private static List<Integer> valuesOf(Data info, String field) {
        Data node = info.getChildByPath(field);
        if (node == null) {
            throw new IllegalStateException("MakeCharInfo is missing field " + field
                    + " - is Etc.wz present and the right version?");
        }
        List<Integer> values = new ArrayList<>();
        for (Data child : node) {
            values.add(DataTool.getInt(child));
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("MakeCharInfo field " + field + " has no options");
        }
        return values;
    }

    private static int pick(Random random, List<Integer> values) {
        return values.get(random.nextInt(values.size()));
    }

    /** The server adds face and hair colour together, so send the sum as the hair id. */
    public int hairWithColor() {
        return hair + hairColor;
    }
}
