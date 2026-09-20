package agents.percept;

import agents.protocol.Opcodes;
import agents.protocol.ServerPackets;
import client.Stat;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns packets into {@link Observation}s.
 *
 * Partial by design: it handles what agents actually meet, and anything else becomes
 * {@link Observation.Unrecognised} so the gap is visible instead of silent. Each decoder
 * names the encoder it mirrors, because the encoders are the only specification of this
 * protocol that is guaranteed to match the server we talk to.
 *
 * A malformed or unexpected packet must never take an agent down, so every decode is
 * guarded: a decoding failure degrades to Unrecognised and is logged.
 */
public class ObservationDecoder {
    private static final Logger log = LoggerFactory.getLogger(ObservationDecoder.class);

    /** Distinguishes the two SET_FIELD forms; see {@link #decodeSetField}. */
    private static final int SET_FIELD_CHARACTER_INFO = 1;
    private static final int SERVER_MESSAGE_TYPE = 4;

    public Observation decode(long tick, int opcode, InPacket p) {
        int available = p.available();
        try {
            Observation observation = decodeKnown(tick, opcode, p);
            return observation != null ? observation : unrecognised(tick, opcode, available);
        } catch (RuntimeException e) {
            log.debug("Failed to decode {} ({} bytes)", Opcodes.describe(opcode), available, e);
            return unrecognised(tick, opcode, available);
        }
    }

    private static Observation unrecognised(long tick, int opcode, int bytes) {
        return new Observation.Unrecognised(tick, opcode, Opcodes.nameOf(opcode), bytes);
    }

    private Observation decodeKnown(long tick, int opcode, InPacket p) {
        if (opcode == SendOpcode.SET_FIELD.getValue()) {
            return decodeSetField(tick, p);
        }
        if (opcode == SendOpcode.STAT_CHANGED.getValue()) {
            return decodeStatChanged(tick, p);
        }
        if (opcode == SendOpcode.SPAWN_PLAYER.getValue()) {
            return decodeSpawnPlayer(tick, p);
        }
        if (opcode == SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue()) {
            return new Observation.PlayerLeft(tick, p.readInt());
        }
        if (opcode == SendOpcode.SPAWN_NPC.getValue()) {
            return decodeSpawnNpc(tick, p);
        }
        if (opcode == SendOpcode.SPAWN_MONSTER.getValue()) {
            return decodeSpawnMonster(tick, p, false);
        }
        if (opcode == SendOpcode.SPAWN_MONSTER_CONTROL.getValue()) {
            return decodeSpawnMonster(tick, p, true);
        }
        if (opcode == SendOpcode.KILL_MONSTER.getValue()) {
            return new Observation.MonsterDied(tick, p.readInt());
        }
        if (opcode == SendOpcode.MOVE_PLAYER.getValue() || opcode == SendOpcode.MOVE_MONSTER.getValue()) {
            return decodeMove(tick, p);
        }
        if (opcode == SendOpcode.DROP_ITEM_FROM_MAPOBJECT.getValue()) {
            return decodeDrop(tick, p);
        }
        if (opcode == SendOpcode.CHATTEXT.getValue()) {
            return decodeChat(tick, p);
        }
        if (opcode == SendOpcode.SERVERMESSAGE.getValue()) {
            return decodeServerMessage(tick, p);
        }
        return null;
    }

    /**
     * SET_FIELD carries either the agent's whole character on entering the world, or a plain
     * map change. They are told apart by the byte after the channel: the character form
     * writes 1 there, the warp form writes the first byte of a zero int.
     *
     * @see tools.PacketCreator#getCharInfo
     * @see tools.PacketCreator#getWarpToMap
     */
    private Observation decodeSetField(long tick, InPacket p) {
        p.readInt();                                    // channel
        int form = p.readUnsignedByte();

        if (form != SET_FIELD_CHARACTER_INFO) {
            p.skip(3);                                  // rest of the zero int
            p.readByte();                               // unused
            int mapId = p.readInt();
            int spawnPoint = p.readUnsignedByte();
            return new Observation.MapEntered(tick, mapId, spawnPoint);
        }

        p.readByte();                                   // second flag
        p.readShort();
        p.skip(12);                                     // three random seeds
        p.readLong();                                   // -1
        p.readByte();
        ServerPackets.CharacterSummary self = ServerPackets.decodeCharacterStats(p);
        return new Observation.SelfDescribed(tick, self.id(), self.name(), self.level(),
                self.job(), self.mapId());
    }

    /**
     * The stats present are named by a bit mask, and each is written at a width that depends
     * on its own bit - so the mask has to be walked in ascending bit order, exactly as the
     * encoder sorts them.
     *
     * @see tools.PacketCreator#updatePlayerStats
     */
    private Observation decodeStatChanged(long tick, InPacket p) {
        p.readByte();                                   // enable actions
        int mask = p.readInt();

        Map<String, Integer> stats = new LinkedHashMap<>();
        Stat[] ordered = Stat.values().clone();
        java.util.Arrays.sort(ordered, Comparator.comparingInt(Stat::getValue));

        for (Stat stat : ordered) {
            if ((mask & stat.getValue()) != stat.getValue()) {
                continue;
            }
            stats.put(stat.name(), readStatValue(p, stat));
        }
        return new Observation.StatsChanged(tick, Map.copyOf(stats));
    }

    private int readStatValue(InPacket p, Stat stat) {
        int value = stat.getValue();
        if (value == 0x1) {
            return p.readByte();
        }
        if (value <= 0x4) {
            return p.readInt();
        }
        if (value < 0x20) {
            return p.readByte();
        }
        if (value < 0xFFFF) {
            // Includes AVAILABLESP, whose variable-width form only applies to jobs with an
            // SP table - none that an agent can reach in v83.
            return p.readShort();
        }
        if (value == 0x20000) {
            return p.readShort();
        }
        return p.readInt();
    }

    /** @see tools.PacketCreator#spawnPlayerMapObject */
    private Observation decodeSpawnPlayer(long tick, InPacket p) {
        int characterId = p.readInt();
        int level = p.readUnsignedByte();
        String name = p.readString();
        return new Observation.PlayerAppeared(tick, characterId, name, level);
    }

    /** @see tools.PacketCreator#spawnNPC */
    private Observation decodeSpawnNpc(long tick, InPacket p) {
        int objectId = p.readInt();
        int npcId = p.readInt();
        int x = p.readShort();
        int y = p.readShort();
        return new Observation.NpcAppeared(tick, objectId, npcId, new Point(x, y));
    }

    /** @see tools.PacketCreator#spawnMonsterInternal */
    private Observation decodeSpawnMonster(long tick, InPacket p, boolean controlForm) {
        if (controlForm) {
            int control = p.readUnsignedByte();
            if (control == 0) {
                // Control being taken away, not a spawn. The object id follows, but the
                // monster is not newly visible, so there is nothing to report.
                return null;
            }
        }
        int objectId = p.readInt();
        p.readByte();                                   // controller flag
        int monsterId = p.readInt();
        p.skip(16);                                     // buff stati, or a skipped block
        int x = p.readShort();
        int y = p.readShort();
        return new Observation.MonsterAppeared(tick, objectId, monsterId, new Point(x, y));
    }

    /**
     * Both movement broadcasts start with the object id, then a movement list in the same
     * encoding the client sends.
     *
     * @see tools.PacketCreator#movePlayer
     */
    private Observation decodeMove(long tick, InPacket p) {
        int objectId = p.readInt();
        p.readInt();
        Point destination = MovementList.finalPosition(p);
        return destination == null ? null : new Observation.ThingMoved(tick, objectId, destination);
    }

    /** @see tools.PacketCreator#dropItemFromMapObject */
    private Observation decodeDrop(long tick, InPacket p) {
        int mod = p.readUnsignedByte();
        int objectId = p.readInt();
        boolean meso = p.readByte() != 0;
        int itemId = p.readInt();
        p.readInt();                                    // owner
        p.readByte();                                   // drop type
        int x = p.readShort();
        int y = p.readShort();
        return new Observation.DropAppeared(tick, objectId, itemId, meso, new Point(x, y));
    }

    /** @see tools.PacketCreator#getChatText */
    private Observation decodeChat(long tick, InPacket p) {
        int speakerId = p.readInt();
        p.readByte();                                   // gm flag
        String text = p.readString();
        return new Observation.ChatHeard(tick, speakerId, text);
    }

    /** @see tools.PacketCreator#serverMessage */
    private Observation decodeServerMessage(long tick, InPacket p) {
        int type = p.readUnsignedByte();
        if (type != SERVER_MESSAGE_TYPE) {
            return new Observation.NoticeShown(tick, p.readString());
        }

        // Type 4 is ambiguous on the wire: the scrolling server message writes a flag byte
        // of 1 before the string, while serverNotice(4, ...) does not. Read the byte and
        // work out which it was - if it is not the flag it is the low half of the string's
        // length, which we can put back together.
        int flagOrLowLength = p.readUnsignedByte();
        if (flagOrLowLength == 1) {
            return new Observation.NoticeShown(tick, p.readString());
        }
        int length = flagOrLowLength | (p.readUnsignedByte() << 8);
        return new Observation.NoticeShown(tick,
                new String(p.readBytes(length), java.nio.charset.StandardCharsets.US_ASCII));
    }
}
