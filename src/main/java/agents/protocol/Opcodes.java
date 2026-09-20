package agents.protocol;

import net.opcodes.SendOpcode;

import java.util.HashMap;
import java.util.Map;

/**
 * Reverse lookup from an opcode value to its name.
 *
 * Unknown packets are a normal part of an agent's life - the decoder handles a subset by
 * design - so they get logged and counted rather than dropped, and a name makes that log
 * something you can act on instead of a column of hex.
 */
public final class Opcodes {
    private static final Map<Integer, String> NAMES = buildNames();

    private Opcodes() {
    }

    private static Map<Integer, String> buildNames() {
        Map<Integer, String> names = new HashMap<>();
        for (SendOpcode opcode : SendOpcode.values()) {
            // Several opcodes share a value in the enum; the first name wins, which is
            // good enough for a log line.
            names.putIfAbsent(opcode.getValue(), opcode.name());
        }
        return Map.copyOf(names);
    }

    public static String nameOf(int opcode) {
        return NAMES.getOrDefault(opcode, "UNKNOWN");
    }

    public static String describe(int opcode) {
        return nameOf(opcode) + "(0x" + Integer.toHexString(opcode) + ")";
    }
}
