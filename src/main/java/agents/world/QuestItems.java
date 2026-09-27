package agents.world;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.HashMap;
import java.util.Map;

/**
 * Whether an item is one a quest needs, as its tooltip says.
 *
 * <p>Item.wz marks them - the letter to a second-job instructor, the Dark Marbles of the test,
 * the Proof of a Hero all carry {@code quest = 1} - and the client shows it on the item. A bag
 * full of snail shells is a reason to leave more shells on the ground; it is never a reason to
 * leave the thirtieth marble there.
 */
public final class QuestItems {

    private QuestItems() {
    }

    private static final Map<Integer, Boolean> CACHE = new HashMap<>();

    public static synchronized boolean isQuestItem(int itemId) {
        return CACHE.computeIfAbsent(itemId, QuestItems::load);
    }

    private static boolean load(int itemId) {
        String folder = switch (itemId / 1000000) {
            case 2 -> "Consume";
            case 3 -> "Install";
            case 4 -> "Etc";
            default -> null;
        };
        if (folder == null) {
            return false;
        }
        DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.ITEM);
        Data group = provider.getData(folder + "/" + String.format("%04d", itemId / 10000) + ".img");
        if (group == null) {
            return false;
        }
        Data item = group.getChildByPath(String.format("%08d", itemId));
        return item != null && DataTool.getInt(item.getChildByPath("info/quest"), 0) != 0;
    }
}
