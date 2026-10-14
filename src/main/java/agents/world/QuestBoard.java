package agents.world;

import provider.Data;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Who finishes a quest - the NPC wearing its marker - or -1 if the data does not say. */
    public static synchronized int enderOf(int questId) {
        loadOnce();
        for (Map.Entry<Integer, List<Integer>> npc : endedByNpc.entrySet()) {
            if (npc.getValue().contains(questId)) {
                return npc.getKey();
            }
        }
        return -1;
    }

    private static void loadOnce() {
        if (offeredByNpc == null) {
            load();
        }
    }

    private static void load() {
        Map<Integer, List<Integer>> byNpc = new HashMap<>();
        Map<Integer, List<Integer>> endNpc = new HashMap<>();
        Map<Integer, Start> starts = new HashMap<>();
        try {
            Data check = WzData.of(WZFiles.QUEST).getData("Check.img");
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
                collectStart(quest.getChildByPath("0"), questId, starts);
            }
        } catch (Exception e) {
            // Without quest data agents simply never start one; not worth failing a run over.
            offeredByNpc = Map.of();
            endedByNpc = Map.of();
            startOf = Map.of();
            return;
        }

        byNpc.values().forEach(java.util.Collections::sort);
        endNpc.values().forEach(java.util.Collections::sort);
        offeredByNpc = Map.copyOf(byNpc);
        endedByNpc = Map.copyOf(endNpc);
        startOf = Map.copyOf(starts);
    }

    /**
     * What it takes to be offered a quest: what the client checks before it draws the marker
     * over an NPC's head - a level range, a job, earlier quests, the dates of an event. Not
     * what the quest asks for once taken, which is still to be found out by doing it.
     */
    private record Start(int lvmin, int lvmax, Set<Integer> jobs, Map<Integer, Integer> quests, String end) {
    }

    private static Map<Integer, Start> startOf;

    /**
     * Whether a quest could be started now - whether a player would see its marker. Henesys has
     * NPCs with dozens of quests each, most for other levels, jobs or long-gone events, and a
     * Bowman spent ten minutes asking for them one after another, four of them taken.
     */
    public static synchronized boolean canStart(int questId, int level, int job, java.util.function.IntFunction<String> stateOf) {
        loadOnce();
        Start start = startOf.get(questId);
        if (start == null) {
            return true;
        }
        if (start.lvmin() > 0 && level < start.lvmin() || start.lvmax() > 0 && level > start.lvmax()) {
            return false;
        }
        if (!start.jobs().isEmpty() && !start.jobs().contains(job)) {
            return false;
        }
        for (Map.Entry<Integer, Integer> needed : start.quests().entrySet()) {
            String state = stateOf.apply(needed.getKey());
            String have = state == null ? "0" : state;
            if (!have.equals(String.valueOf(needed.getValue()))) {
                return false;
            }
        }
        if (start.end() != null && start.end().compareTo(NOW) < 0) {
            return false;   // an event long over
        }
        return true;
    }

    private static final String NOW = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHH"));

    private static void collectStart(Data step, int questId, Map<Integer, Start> into) {
        if (step == null) {
            return;
        }
        Set<Integer> jobs = new java.util.HashSet<>();
        Data job = step.getChildByPath("job");
        if (job != null) {
            for (Data one : job.getChildren()) {
                jobs.add(DataTool.getInt(one, -1));
            }
        }
        Map<Integer, Integer> quests = new HashMap<>();
        Data quest = step.getChildByPath("quest");
        if (quest != null) {
            for (Data one : quest.getChildren()) {
                quests.put(DataTool.getInt(one.getChildByPath("id"), -1), DataTool.getInt(one.getChildByPath("state"), 2));
            }
        }
        String end = DataTool.getString(step.getChildByPath("end"), null);
        into.put(questId, new Start(DataTool.getInt(step.getChildByPath("lvmin"), 0),
                DataTool.getInt(step.getChildByPath("lvmax"), 0), Set.copyOf(jobs), Map.copyOf(quests), end));
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
