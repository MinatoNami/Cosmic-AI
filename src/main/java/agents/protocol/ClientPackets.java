package agents.protocol;

import net.opcodes.RecvOpcode;
import net.packet.ByteBufOutPacket;
import net.packet.OutPacket;
import net.packet.Packet;

import java.awt.Point;

/**
 * Builders for the packets a client sends. The server's own {@code PacketCreator} covers the
 * other direction; this is its counterpart, and it is deliberately small - an agent only
 * needs the handful of actions it can actually take.
 *
 * Field layouts are read off the matching handler in {@code net.server.*.handlers}, which is
 * the only specification of this protocol that is guaranteed to match the server we talk to.
 */
public final class ClientPackets {
    private ClientPackets() {
    }

    private static OutPacket packet(RecvOpcode opcode) {
        OutPacket p = new ByteBufOutPacket();
        p.writeShort(opcode.getValue());
        return p;
    }

    // ---------------------------------------------------------------- login server

    /** @see net.server.handlers.login.LoginPasswordHandler */
    public static Packet login(String account, String password, byte[] hwidNibbles) {
        OutPacket p = packet(RecvOpcode.LOGIN_PASSWORD);
        p.writeString(account);
        p.writeString(password);
        p.writeBytes(hwidNibbles);
        return p;
    }

    /** @see net.server.handlers.login.ServerlistRequestHandler */
    public static Packet serverListRequest() {
        return packet(RecvOpcode.SERVERLIST_REQUEST);
    }

    /** @see net.server.handlers.login.ServerStatusRequestHandler */
    public static Packet serverStatusRequest(int world) {
        OutPacket p = packet(RecvOpcode.SERVERSTATUS_REQUEST);
        p.writeShort(world);
        return p;
    }

    /**
     * @param channel 1-based, as the channel list shows it; the server reads it 0-based
     * @see net.server.handlers.login.CharlistRequestHandler
     */
    public static Packet characterListRequest(int world, int channel) {
        OutPacket p = packet(RecvOpcode.CHARLIST_REQUEST);
        p.writeByte(0);
        p.writeByte(world);
        p.writeByte(channel - 1);
        return p;
    }

    /**
     * Host string must match {@code [0-9A-F]{12}_[0-9A-F]{8}} or the server rejects the
     * session outright.
     *
     * @see net.server.handlers.login.CharSelectedHandler
     */
    public static Packet selectCharacter(int characterId, String macs, String hostString) {
        OutPacket p = packet(RecvOpcode.CHAR_SELECT);
        p.writeInt(characterId);
        p.writeString(macs);
        p.writeString(hostString);
        return p;
    }

    // -------------------------------------------------------------- channel server

    /** First packet on a channel connection, after the handshake. */
    public static Packet playerLoggedIn(int characterId) {
        OutPacket p = packet(RecvOpcode.PLAYER_LOGGEDIN);
        p.writeInt(characterId);
        return p;
    }

    /**
     * One absolute-move fragment, which is all the server needs: {@code MovePlayerHandler}
     * parses the command list and then simply takes the resulting position.
     *
     * @param stance even values face right, odd face left; 0 is standing
     */
    public static Packet move(Point from, Point to, short foothold, byte stance, short durationMillis) {
        OutPacket p = packet(RecvOpcode.MOVE_PLAYER);
        p.writeByte(0);             // portal count
        p.writeInt(0);              // unused
        p.writeInt(0);              // unused
        p.writeByte(1);             // one movement command follows
        p.writeByte(0);             // command 0: absolute move
        p.writeShort(to.x);
        p.writeShort(to.y);
        p.writeShort(to.x - from.x);    // wobble, read as pixels per second
        p.writeShort(to.y - from.y);
        p.writeShort(foothold);
        p.writeByte(stance);
        p.writeShort(durationMillis);
        p.writeByte(0);             // key-down state count
        return p;
    }

    /**
     * Messages over 127 characters trip the packet-edit autoban, so keep them short.
     *
     * @see net.server.channel.handlers.GeneralchatHandler
     */
    public static Packet chat(String message, boolean shownToGm) {
        OutPacket p = packet(RecvOpcode.GENERAL_CHAT);
        p.writeString(message);
        p.writeByte(shownToGm ? 1 : 0);
        return p;
    }

    /** @see net.server.channel.handlers.NPCTalkHandler */
    public static Packet talkToNpc(int objectId) {
        OutPacket p = packet(RecvOpcode.NPC_TALK);
        p.writeInt(objectId);
        return p;
    }

    /** Advances or answers an open NPC dialogue. */
    public static Packet npcTalkMore(byte lastMessageType, byte action, int selection) {
        OutPacket p = packet(RecvOpcode.NPC_TALK_MORE);
        p.writeByte(lastMessageType);
        p.writeByte(action);
        if (selection >= 0) {
            p.writeInt(selection);
        }
        return p;
    }
}
