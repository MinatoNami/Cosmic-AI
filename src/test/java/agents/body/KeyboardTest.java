package agents.body;

import agents.percept.Item;
import agents.percept.Observation;
import agents.world.Inventory;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyboardTest {

    private static final int RED_POTION = 2000000;

    private final Keyboard keyboard = new Keyboard();
    private final Inventory inventory = new Inventory();

    private static InPacket read(Packet packet, RecvOpcode expected) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        assertEquals(expected.getValue(), in.readShort() & 0xFFFF);
        return in;
    }

    private void keysAre(Map<Integer, Observation.KeysBound.Binding> keys) {
        keyboard.update(new Observation.KeysBound(1, keys));
    }

    @Test
    void bindsAPotionToTheFirstFreePotionKey() {
        keysAre(Map.of(83, new Observation.KeysBound.Binding(4, 1)));

        InPacket p = read(keyboard.bind(RED_POTION).orElseThrow(), RecvOpcode.CHANGE_KEYMAP);

        assertEquals(0, p.readInt(), "ordinary keys");
        assertEquals(1, p.readInt());
        assertEquals(79, p.readInt(), "Delete is taken, so End");
        assertEquals(Keyboard.ITEM, p.readByte());
        assertEquals(RED_POTION, p.readInt());
        assertEquals(79, keyboard.keyFor(RED_POTION).orElseThrow());
    }

    @Test
    void doesNotBindWhatIsAlreadyBoundNorBeforeItKnowsTheKeys() {
        assertTrue(keyboard.bind(RED_POTION).isEmpty(), "keys not described yet");

        keysAre(Map.of(83, new Observation.KeysBound.Binding(Keyboard.ITEM, RED_POTION)));
        assertTrue(keyboard.bind(RED_POTION).isEmpty());
    }

    @Test
    void goesWithoutWhenEveryPotionKeyIsTaken() {
        Map<Integer, Observation.KeysBound.Binding> full = new HashMap<>();
        Keyboard.POTION_KEYS.forEach(key -> full.put(key, new Observation.KeysBound.Binding(4, key)));
        keysAre(full);

        assertTrue(keyboard.bind(RED_POTION).isEmpty());
    }

    /** Pressing the key uses the first stack, as the client does. */
    @Test
    void pressingThePotionKeyUsesTheFirstStack() {
        keysAre(Map.of(83, new Observation.KeysBound.Binding(Keyboard.ITEM, RED_POTION)));
        inventory.update(new Observation.InventoryShown(1, 0, Map.of(1, 24, 2, 24, 3, 24, 4, 24, 5, 24),
                List.of(new Item(2, 4, RED_POTION, 3, null), new Item(2, 7, RED_POTION, 9, null))));

        InPacket p = read(keyboard.press(83, inventory).orElseThrow(), RecvOpcode.USE_ITEM);

        p.readInt();
        assertEquals(4, p.readShort());
        assertEquals(RED_POTION, p.readInt());
    }

    @Test
    void pressingAKeyForSomethingNotCarriedDoesNothing() {
        keysAre(Map.of(83, new Observation.KeysBound.Binding(Keyboard.ITEM, RED_POTION)));

        assertTrue(keyboard.press(83, inventory).isEmpty());
        assertTrue(keyboard.press(12, inventory).isEmpty());
    }
}
