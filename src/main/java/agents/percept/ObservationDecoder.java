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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    /**
     * Monsters reported as vanishing whose second removal packet is still to come. A set
     * because the server's threads can put another monster's packets between the two halves.
     * See {@link #decodeKill}.
     */
    private final Set<Integer> vanishing = new HashSet<>();

    /**
     * Stands in for a packet that only repeats what an earlier one said. Compared by
     * identity and never handed out, so what type it borrows does not matter.
     */
    private static final Observation ALREADY_SAID = new Observation.NoticeShown(-1, "");

    /**
     * @return what the packet says, or null when it only repeats the one before it - which
     *         is not something that happened, and so is not an observation at all
     */
    public Observation decode(long tick, int opcode, InPacket p) {
        int available = p.available();
        try {
            Observation observation = decodeKnown(tick, opcode, p);
            if (observation == ALREADY_SAID) {
                return null;
            }
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
            return decodeKill(tick, p);
        }
        if (opcode == SendOpcode.MOVE_PLAYER.getValue()) {
            return decodeMove(tick, p);
        }
        if (opcode == SendOpcode.MOVE_MONSTER.getValue()) {
            return decodeMonsterMove(tick, p);
        }
        if (opcode == SendOpcode.DROP_ITEM_FROM_MAPOBJECT.getValue()) {
            return decodeDrop(tick, p);
        }
        if (opcode == SendOpcode.REMOVE_ITEM_FROM_MAP.getValue()) {
            return decodeDropRemoved(tick, p);
        }
        if (opcode == SendOpcode.SHOW_MONSTER_HP.getValue()) {
            return new Observation.MonsterHurt(tick, p.readInt(), p.readUnsignedByte());
        }
        if (opcode == SendOpcode.DAMAGE_PLAYER.getValue()) {
            return decodeDamagePlayer(tick, p);
        }
        if (opcode == SendOpcode.SHOW_ITEM_GAIN_INCHAT.getValue()) {
            return decodeItemGainInChat(tick, p);
        }
        if (opcode == SendOpcode.CHATTEXT.getValue()) {
            return decodeChat(tick, p);
        }
        if (opcode == SendOpcode.SERVERMESSAGE.getValue()) {
            return decodeServerMessage(tick, p);
        }
        if (opcode == SendOpcode.WHISPER.getValue()) {
            return decodeWhisper(tick, p);
        }
        if (opcode == SendOpcode.NPC_TALK.getValue()) {
            return decodeDialogue(tick, p);
        }
        if (opcode == SendOpcode.SHOW_STATUS_INFO.getValue()) {
            return decodeStatusInfo(tick, p);
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
                self.job(), self.mapId(), self.spawnPoint());
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

    /**
     * Only the plain form is a sighting.
     *
     * The control form hands the agent's client a monster to steer - or takes one away -
     * and the server only ever sends it for a monster it has already shown with the plain
     * form. Reading it as a sighting had agents seeing each monster appear twice, and after
     * every arrival in a map a third and fourth time. It also carries a buff block of
     * variable length where the plain form has sixteen fixed bytes, so reading a position
     * out of it at the plain form's offset was wrong for any monster with a status on it.
     *
     * @see tools.PacketCreator#spawnMonsterInternal
     * @see net.server.channel.handlers.PlayerMapTransitionHandler
     */
    private Observation decodeSpawnMonster(long tick, InPacket p, boolean controlForm) {
        if (controlForm) {
            return null;
        }
        int objectId = p.readInt();
        p.readByte();                                   // controller flag
        int monsterId = p.readInt();
        p.skip(16);                                     // buff stati, or a skipped block
        int x = p.readShort();
        int y = p.readShort();
        return new Observation.MonsterAppeared(tick, objectId, monsterId, new Point(x, y));
    }

    /** @see tools.PacketCreator#movePlayer */
    private Observation decodeMove(long tick, InPacket p) {
        int objectId = p.readInt();
        p.readInt();
        Point destination = MovementList.finalPosition(p);
        return destination == null ? null : new Observation.ThingMoved(tick, objectId, destination);
    }

    /**
     * A monster's movement has seven bytes of skill fields and its starting position between
     * the object id and the list, where a player's has four. This used to be read with the
     * player layout, which lands in the middle of the skill fields and reads them as moves.
     *
     * The list may hold only relative moves; the start is still a position the server stated
     * outright, and a better answer than none.
     *
     * @see tools.PacketCreator#moveMonster
     */
    private Observation decodeMonsterMove(long tick, InPacket p) {
        int objectId = p.readInt();
        p.skip(7);                                      // flag, skill possible, skill, id, level, option
        Point start = new Point(p.readShort(), p.readShort());
        Point destination = MovementList.finalPosition(p);
        return new Observation.ThingMoved(tick, objectId, destination != null ? destination : start);
    }

    /**
     * A monster leaving the screen, told apart by the animation to play: 0 is simply
     * disappearing, anything else is a death.
     *
     * Disappearing is sent as a pair, 0 then 1, for the same monster: that is how the server
     * takes a monster off one client's screen - out of range, or wiped on every arrival in a
     * map before being shown again. Read naively the second half is a death, and every agent
     * watched every monster in a map die each time it walked in.
     *
     * @see tools.PacketCreator#killMonster
     * @see server.life.Monster#sendDestroyData
     */
    private Observation decodeKill(long tick, InPacket p) {
        int objectId = p.readInt();
        int animation = p.readUnsignedByte();

        if (animation == KILL_DISAPPEAR) {
            vanishing.add(objectId);
            return new Observation.MonsterVanished(tick, objectId);
        }
        return vanishing.remove(objectId) ? ALREADY_SAID : new Observation.MonsterDied(tick, objectId);
    }

    private static final int KILL_DISAPPEAR = 0;

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

    /**
     * What an NPC said, and what kind of answer it wants.
     *
     * The style is worth keeping even though the agent cannot read the text: it is the
     * difference between something that needs a yes and something that just needs
     * acknowledging, which is enough to hold a conversation without understanding it.
     *
     * @see tools.PacketCreator#getNPCTalk
     */
    private Observation decodeDialogue(long tick, InPacket p) {
        p.readByte();                                   // always 4
        int npcId = p.readInt();
        int style = p.readUnsignedByte();
        p.readByte();                                   // speaker
        return new Observation.DialogueShown(tick, npcId, p.readString(), style);
    }

    /**
     * SHOW_STATUS_INFO carries several unrelated updates, told apart by a leading byte. Only
     * the quest one is decoded; the rest are item and meso gains we do not read yet.
     *
     * @see tools.PacketCreator#updateQuest
     */
    private Observation decodeStatusInfo(long tick, InPacket p) {
        int kind = p.readUnsignedByte();
        if (kind == STATUS_INFO_PICKUP) {
            return decodePickupMessage(tick, p);
        }
        if (kind == STATUS_INFO_EXP) {
            p.readByte();                               // white text or yellow
            return new Observation.ExpGained(tick, p.readInt());
        }
        if (kind == STATUS_INFO_MESO_IN_CHAT) {
            return new Observation.MesoGained(tick, p.readInt());
        }
        if (kind != STATUS_INFO_QUEST) {
            return null;
        }
        int questId = p.readShort() & 0xFFFF;
        int state = p.readUnsignedByte();
        return new Observation.QuestStateChanged(tick, questId, state);
    }

    /**
     * The messages that scroll up the corner of the screen as you pick things up, told apart
     * by a second byte: what went in the bag, how much money, or that nothing would fit.
     *
     * @see tools.PacketCreator#getShowItemGain(int, short, boolean)
     * @see tools.PacketCreator#getShowMesoGain(int, boolean)
     * @see tools.PacketCreator#getShowInventoryStatus
     */
    private Observation decodePickupMessage(long tick, InPacket p) {
        int what = p.readUnsignedByte();
        if (what == PICKED_UP_ITEM) {
            int itemId = p.readInt();
            return new Observation.ItemGained(tick, itemId, p.readInt());
        }
        if (what == PICKED_UP_MESO) {
            p.readByte();                               // high byte of the short the encoder wrote
            return new Observation.MesoGained(tick, p.readInt());
        }
        // The same message a player sees as "you cannot hold any more". Without it an agent
        // with a full bag tried to pick the same drop up hundreds of times.
        return what == INVENTORY_FULL ? new Observation.InventoryFull(tick) : null;
    }

    private static final int STATUS_INFO_PICKUP = 0;
    private static final int STATUS_INFO_QUEST = 1;
    private static final int STATUS_INFO_EXP = 3;
    private static final int STATUS_INFO_MESO_IN_CHAT = 5;
    private static final int PICKED_UP_ITEM = 0;
    private static final int PICKED_UP_MESO = 1;
    private static final int INVENTORY_FULL = 0xFF;

    /**
     * An item handed over rather than picked up - a quest reward, a purchase - announced in
     * the chat log. The opcode carries several other effects; only this one is a gain.
     *
     * @see tools.PacketCreator#getShowItemGain(int, short, boolean)
     */
    private Observation decodeItemGainInChat(long tick, InPacket p) {
        if (p.readUnsignedByte() != ITEM_GAIN_IN_CHAT) {
            return null;
        }
        p.readByte();                                   // how many kinds follow; always one
        int itemId = p.readInt();
        return new Observation.ItemGained(tick, itemId, p.readInt());
    }

    private static final int ITEM_GAIN_IN_CHAT = 3;

    /**
     * Who picked it up is only written for a pick-up; an expiry names nobody.
     *
     * @see tools.PacketCreator#removeItemFromMap(int, int, int, boolean, int)
     */
    private Observation decodeDropRemoved(long tick, InPacket p) {
        int how = p.readUnsignedByte();
        int objectId = p.readInt();
        int takenBy = how >= DROP_PICKED_UP ? p.readInt() : Observation.DropTaken.NOBODY;
        return new Observation.DropTaken(tick, objectId, takenBy);
    }

    private static final int DROP_PICKED_UP = 2;

    /**
     * Somebody else being hit. The server sends this to everyone in the map but the victim,
     * who learns it from their own health going down.
     *
     * @see tools.PacketCreator#damagePlayer
     */
    private Observation decodeDamagePlayer(long tick, InPacket p) {
        int characterId = p.readInt();
        int from = p.readByte();
        if (from == DAMAGE_FROM_MAP_OBJECT) {
            p.readInt();
        }
        int damage = p.readInt();
        int monsterId = from == DAMAGE_FROM_FALLING ? 0 : p.readInt();
        return new Observation.PlayerHurt(tick, characterId, damage, monsterId);
    }

    private static final int DAMAGE_FROM_MAP_OBJECT = -3;
    private static final int DAMAGE_FROM_FALLING = -4;

    /**
     * The whisper opcode carries several unrelated things - delivery receipts, /find results
     * - told apart by a flag. Only an incoming message is an observation.
     *
     * @see tools.PacketCreator#getWhisperReceive
     */
    private Observation decodeWhisper(long tick, InPacket p) {
        int flag = p.readUnsignedByte();
        if ((flag & WHISPER_RECEIVE) == 0) {
            return null;
        }
        String sender = p.readString();
        p.readByte();                                   // channel
        p.readByte();                                   // from an admin
        return new Observation.WhisperHeard(tick, sender, p.readString());
    }

    /** @see tools.PacketCreator.WhisperFlag */
    private static final int WHISPER_RECEIVE = 0x10;

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
