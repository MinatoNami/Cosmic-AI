package agents.mind;

import agents.percept.Item;
import agents.protocol.ClientPackets;
import agents.world.QuestItems;
import agents.world.WorldModel;
import net.packet.Packet;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Drops something ordinary to make room for something a quest needs.
 *
 * <p>With a full bag the server refuses a pick-up however much the item matters, so being
 * willing to take a quest item is not enough - there has to be a slot for it. When one is lying
 * in sight and its bag has none, the cheapest-looking ordinary stack in that bag goes on the
 * floor: never a quest item, never anything worn. One stack at a time, and not again until the
 * bag has had a moment to change.
 */
public final class MakingRoom {

    /** Steps between drops, so one full bag costs one stack, not a bagful. */
    static final int BETWEEN_DROPS = 10;

    private int cooldown;

    /** The one stack worth dropping now, if any. */
    public Optional<Packet> step(WorldModel world) {
        if (cooldown > 0) {
            cooldown--;
            return Optional.empty();
        }
        if (!world.inventory().known() || world.isDead()) {
            return Optional.empty();
        }
        List<WorldModel.Entity> waiting = world.questDropsWithoutRoom();
        if (waiting.isEmpty()) {
            return Optional.empty();
        }
        int type = Item.typeOf(waiting.get(0).typeId());
        Optional<Item> ordinary = world.inventory().carried(type).stream()
                .filter(item -> !QuestItems.isQuestItem(item.itemId()))
                .min(Comparator.comparingInt(Item::quantity));
        if (ordinary.isEmpty()) {
            return Optional.empty();        // nothing here that is not needed; leave it
        }
        cooldown = BETWEEN_DROPS;
        Item drop = ordinary.get();
        return Optional.of(ClientPackets.moveItem(type, drop.slot(), 0, Math.max(1, drop.quantity())));
    }
}
