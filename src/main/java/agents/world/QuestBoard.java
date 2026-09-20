package agents.world;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which NPCs have something to offer.
 *
 * Read from {@code Quest.wz/Check.img}, which every client loads to draw the marker over an
 * NPC's head - so knowing that npc 2101 has <em>something</em> is what a player sees on
 * screen, and an agent may see it too.
 *
 * <strong>What is deliberately not read: what a quest asks for, and what it gives.</strong>
 * That lives in the same file, one node deeper, and taking it would hand an agent the answer
 * to the question it is supposed to work out - that killing these things and coming back
 * here is rewarded. An agent finds out by starting one and watching what follows, exactly as
 * a person reads the dialogue and then goes to find out what a Blue Snail is.
 */
public class QuestBoard {

    private static Map<Integer, List<Integer>> offeredByNpc;
    private static Map<Integer, List<Integer>> endedByNpc;

    private QuestBoard() {
    }

    /** Quest ids this NPC starts, in ascending order. Empty if it starts none. */
    public static synchronized List<Integer> offeredBy(int npcId) {
        loadOnce();
        return offeredByNpc.getOrDefault(npcId, List.of());
    }

    /**
     * Quest ids this NPC finishes. The client draws a marker over the NPC who can take a
     * quest back, the same as it does for the one who gives it out, so an agent may see
     * where to return - but not what it has to bring.
     */
    public static synchronized List<Integer> endedBy(int npcId) {
        loadOnce();
        return endedByNpc.getOrDefault(npcId, List.of());
    }

    private static void loadOnce() {
        if (offeredByNpc == null) {
            load();
        }
    }

    private static void load() {
        Map<Integer, List<Integer>> byNpc = new HashMap<>();
        Map<Integer, List<Integer>> endNpc = new HashMap<>();
        try {
            Data check = DataProviderFactory.getDataProvider(WZFiles.QUEST).getData("Check.img");
            for (Data quest : check.getChildren()) {
                int questId = parseOrSkip(quest.getName());
                if (questId < 0) {
                    continue;
                }
                // Step "0" is what it takes to start and "1" what it takes to finish. Only
                // the npc is read from either; the rest of each node is the requirement list,
                // which is the thing an agent is supposed to discover by doing it.
                collectNpc(quest.getChildByPath("0"), questId, byNpc);
                collectNpc(quest.getChildByPath("1"), questId, endNpc);
            }
        } catch (Exception e) {
            // Without quest data agents simply never start one; not worth failing a run over.
            offeredByNpc = Map.of();
            endedByNpc = Map.of();
            return;
        }

        byNpc.values().forEach(java.util.Collections::sort);
        endNpc.values().forEach(java.util.Collections::sort);
        offeredByNpc = Map.copyOf(byNpc);
        endedByNpc = Map.copyOf(endNpc);
    }

    private static void collectNpc(Data step, int questId, Map<Integer, List<Integer>> into) {
        if (step == null) {
            return;
        }
        int npcId = DataTool.getInt(step.getChildByPath("npc"), -1);
        if (npcId > 0) {
            into.computeIfAbsent(npcId, id -> new ArrayList<>()).add(questId);
        }
    }

    private static int parseOrSkip(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
