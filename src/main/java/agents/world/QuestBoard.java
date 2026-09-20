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

    private QuestBoard() {
    }

    /** Quest ids this NPC starts, in ascending order. Empty if it starts none. */
    public static synchronized List<Integer> offeredBy(int npcId) {
        if (offeredByNpc == null) {
            offeredByNpc = load();
        }
        return offeredByNpc.getOrDefault(npcId, List.of());
    }

    private static Map<Integer, List<Integer>> load() {
        Map<Integer, List<Integer>> byNpc = new HashMap<>();
        try {
            Data check = DataProviderFactory.getDataProvider(WZFiles.QUEST).getData("Check.img");
            for (Data quest : check.getChildren()) {
                int questId = parseOrSkip(quest.getName());
                if (questId < 0) {
                    continue;
                }
                // Step "0" is what it takes to start, and the npc named there is who starts it.
                Data start = quest.getChildByPath("0");
                if (start == null) {
                    continue;
                }
                int npcId = DataTool.getInt(start.getChildByPath("npc"), -1);
                if (npcId > 0) {
                    byNpc.computeIfAbsent(npcId, id -> new ArrayList<>()).add(questId);
                }
            }
        } catch (Exception e) {
            // Without quest data agents simply never start one; not worth failing a run over.
            return Map.of();
        }

        byNpc.values().forEach(java.util.Collections::sort);
        return Map.copyOf(byNpc);
    }

    private static int parseOrSkip(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
