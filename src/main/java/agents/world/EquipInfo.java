package agents.world;

import constants.inventory.EquipSlot;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataFileEntry;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.HashMap;
import java.util.Map;

/**
 * What a piece of equipment's tooltip says about wearing it: where it goes and what it takes.
 *
 * Read from {@code Character.wz}, which every client loads to draw the character and fill in
 * the tooltip - the requirements printed in red when you fall short of them. That is the
 * whole of it: no hidden price, no drop rates, nothing a player hovering over the item would
 * not be shown.
 *
 * @param slots   where on the body it may go, most preferred first; empty if nowhere
 * @param reqJob  0 for anyone, otherwise a mask of the job families that may wear it
 */
public record EquipInfo(int[] slots, int reqLevel, int reqJob, int reqStr, int reqDex, int reqInt,
                        int reqLuk, boolean cash, boolean overall) {

    public static final EquipInfo UNKNOWN =
            new EquipInfo(new int[0], Integer.MAX_VALUE, 0, 0, 0, 0, 0, false, false);

    private static final Map<Integer, EquipInfo> CACHE = new HashMap<>();
    private static Map<String, String> folderOf;

    public static synchronized EquipInfo of(int itemId) {
        return CACHE.computeIfAbsent(itemId, EquipInfo::load);
    }

    private static EquipInfo load(int itemId) {
        DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.CHARACTER);
        String file = "0" + itemId + ".img";
        String folder = folders(provider).get(file);
        if (folder == null) {
            return UNKNOWN;
        }
        Data item = provider.getData(folder + "/" + file);
        Data info = item == null ? null : item.getChildByPath("info");
        if (info == null) {
            return UNKNOWN;
        }
        String islot = DataTool.getString("islot", info, "");
        EquipSlot slot = EquipSlot.getFromTextSlot(islot);
        return new EquipInfo(slotsFor(slot),
                DataTool.getIntConvert("reqLevel", info, 0),
                DataTool.getIntConvert("reqJob", info, 0),
                DataTool.getIntConvert("reqSTR", info, 0),
                DataTool.getIntConvert("reqDEX", info, 0),
                DataTool.getIntConvert("reqINT", info, 0),
                DataTool.getIntConvert("reqLUK", info, 0),
                DataTool.getIntConvert("cash", info, 0) == 1,
                slot == EquipSlot.OVERALL);
    }

    /** The body slots an EquipSlot allows, the same table the server checks against. */
    static int[] slotsFor(EquipSlot slot) {
        return switch (slot) {
            case HAT, SPECIAL_HAT -> new int[]{-1};
            case FACE_ACCESSORY -> new int[]{-2};
            case EYE_ACCESSORY -> new int[]{-3};
            case EARRINGS -> new int[]{-4};
            case TOP, OVERALL -> new int[]{-5};
            case PANTS -> new int[]{-6};
            case SHOES -> new int[]{-7};
            case GLOVES, CASH_GLOVES -> new int[]{-8};
            case CAPE -> new int[]{-9};
            case SHIELD -> new int[]{-10};
            case WEAPON, WEAPON_2, LOW_WEAPON -> new int[]{-11};
            case RING -> new int[]{-12, -13, -15, -16};
            case PENDANT -> new int[]{-17};
            case MEDAL -> new int[]{-49};
            case BELT -> new int[]{-50};
            default -> new int[0];      // mounts, saddles, pet gear: not worn for stats
        };
    }

    /** Which folder of Character.wz each item's file is in, found once by walking it. */
    private static Map<String, String> folders(DataProvider provider) {
        if (folderOf == null) {
            folderOf = new HashMap<>();
            for (DataDirectoryEntry folder : provider.getRoot().getSubdirectories()) {
                for (DataFileEntry file : folder.getFiles()) {
                    folderOf.put(file.getName(), folder.getName());
                }
            }
        }
        return folderOf;
    }

    /**
     * Whether a character of this job family may wear it. The job mask is 1 warrior, 2
     * magician, 4 bowman, 8 thief, 16 pirate; a beginner may wear only what anyone may.
     */
    public boolean allowsJob(int job) {
        if (reqJob <= 0) {
            return true;
        }
        int family = (job % 1000) / 100;
        return family > 0 && (reqJob & (1 << (family - 1))) != 0;
    }
}
