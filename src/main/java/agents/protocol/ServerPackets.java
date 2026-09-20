package agents.protocol;

import net.packet.InPacket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoders for the login-phase packets, which are protocol plumbing rather than anything an
 * agent perceives. World observations are decoded separately.
 *
 * Each decoder mirrors the encoder named in its javadoc. Where the encoder branches, the
 * branch we do not handle is stated explicitly rather than silently mis-parsed.
 */
public final class ServerPackets {
    private ServerPackets() {
    }

    public record LoginResult(boolean success, int reason, int accountId, String accountName, int gender) {
        public static LoginResult failed(int reason) {
            return new LoginResult(false, reason, -1, null, -1);
        }
    }

    public record CharacterSummary(int id, String name, int level, int job, int mapId) {
    }

    public record ChannelHandoff(String host, int port, int characterId) {
    }

    /**
     * Success and failure share an opcode and are told apart by the first byte: a failure
     * writes its reason there, and success begins with a zero int.
     *
     * @see tools.PacketCreator#getAuthSuccess
     * @see tools.PacketCreator#getLoginFailed
     */
    public static LoginResult decodeLoginStatus(InPacket p) {
        int reason = p.readUnsignedByte();
        if (reason != 0) {
            return LoginResult.failed(reason);
        }

        p.skip(5);                          // rest of the leading int, then a zero short
        int accountId = p.readInt();
        int gender = p.readUnsignedByte();
        p.readByte();                       // admin flag
        p.readByte();                       // admin byte
        p.readByte();                       // country code
        String accountName = p.readString();

        return new LoginResult(true, 0, accountId, accountName, gender);
    }

    /**
     * @see tools.PacketCreator#getCharList
     */
    public static List<CharacterSummary> decodeCharacterList(InPacket p) {
        p.readByte();                       // status
        int count = p.readUnsignedByte();

        List<CharacterSummary> characters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            characters.add(decodeCharacterEntry(p));
        }
        return characters;
    }

    /** @see tools.PacketCreator#getServerIP */
    public static ChannelHandoff decodeServerIp(InPacket p) {
        p.readShort();
        byte[] address = p.readBytes(4);
        int port = p.readShort() & 0xFFFF;
        int characterId = p.readInt();

        String host = (address[0] & 0xFF) + "." + (address[1] & 0xFF) + "."
                + (address[2] & 0xFF) + "." + (address[3] & 0xFF);
        return new ChannelHandoff(host, port, characterId);
    }

    private static CharacterSummary decodeCharacterEntry(InPacket p) {
        CharacterSummary summary = decodeCharacterStats(p);
        skipCharacterLook(p);
        p.readByte();                       // character-card slot marker

        int rankEnabled = p.readUnsignedByte();
        if (rankEnabled == 1) {
            p.skip(16);                     // world rank, move, job rank, move
        }
        return summary;
    }

    /**
     * Assumes the job has no SP table, which holds for every job reachable in v83 through
     * normal play. An Evan-style job would add a variable-length skill block here and throw
     * the rest of the list out of alignment.
     *
     * @see tools.PacketCreator#addCharStats
     */
    public static CharacterSummary decodeCharacterStats(InPacket p) {
        int id = p.readInt();
        String name = readFixedString(p, 13);
        p.readByte();                       // gender
        p.readByte();                       // skin
        p.readInt();                        // face
        p.readInt();                        // hair
        p.skip(24);                         // three pet unique ids
        int level = p.readUnsignedByte();
        int job = p.readShort();
        p.skip(8);                          // str, dex, int, luk
        p.skip(8);                          // hp, maxhp, mp, maxmp
        p.readShort();                      // remaining ap
        p.readShort();                      // remaining sp
        p.readInt();                        // exp
        p.readShort();                      // fame
        p.readInt();                        // gacha exp
        int mapId = p.readInt();
        p.readByte();                       // spawn point
        p.readInt();

        return new CharacterSummary(id, name, level, job, mapId);
    }

    /** @see tools.PacketCreator#addCharLook */
    private static void skipCharacterLook(InPacket p) {
        p.readByte();                       // gender
        p.readByte();                       // skin
        p.readInt();                        // face
        p.readByte();                       // not-a-megaphone flag
        p.readInt();                        // hair
        skipEquipList(p);                   // visible equips
        skipEquipList(p);                   // masked equips
        p.readInt();                        // cash weapon
        p.skip(12);                         // three pet item ids
    }

    /** Slot/item pairs terminated by a 0xFF slot. */
    private static void skipEquipList(InPacket p) {
        while (p.readUnsignedByte() != 0xFF) {
            p.readInt();
        }
    }

    private static String readFixedString(InPacket p, int length) {
        byte[] bytes = p.readBytes(length);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.US_ASCII);
    }
}
