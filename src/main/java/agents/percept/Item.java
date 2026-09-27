package agents.percept;

/**
 * Something in the agent's bag, or worn.
 *
 * Like everything else an agent perceives, an item is a number: {@code itemId} says nothing
 * about what the thing is for. The equipment numbers are the exception, because they are
 * printed on the item's tooltip for any player to read - which is exactly what the bonuses
 * on a piece of armour are.
 *
 * @param type  the bag it is in: 1 equipment, 2 use, 3 set-up, 4 etc, 5 cash
 * @param slot  where in that bag, from 1; negative for something being worn, where the
 *              number says where on the body
 * @param stats what wearing it adds, or null for anything that is not equipment
 */
public record Item(int type, int slot, int itemId, int quantity, EquipStats stats) {

    public static final int EQUIP = 1;
    public static final int USE = 2;
    public static final int SETUP = 3;
    public static final int ETC = 4;
    public static final int CASH = 5;

    public boolean isWorn() {
        return slot < 0;
    }

    public Item at(int newSlot) {
        return new Item(type, newSlot, itemId, quantity, stats);
    }

    public Item withQuantity(int newQuantity) {
        return new Item(type, slot, itemId, newQuantity, stats);
    }

    /** The bag an item id belongs in, which the id's first digit says. */
    public static int typeOf(int itemId) {
        return itemId / 1_000_000;
    }

    /** What a piece of equipment adds when worn, as its tooltip lists it. */
    public record EquipStats(int str, int dex, int intel, int luk, int hp, int mp,
                             int watk, int matk, int wdef, int mdef, int acc, int avoid,
                             int speed, int jump, int upgradeSlots) {

        public static final EquipStats NONE =
                new EquipStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
