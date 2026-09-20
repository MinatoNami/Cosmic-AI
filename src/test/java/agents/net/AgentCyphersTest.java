package agents.net;

import net.encryption.ClientCyphers;
import net.encryption.InitializationVector;
import net.encryption.MapleAESOFB;
import net.encryption.MapleCustomEncryption;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent's cyphers have to be the exact mirror of the server's. Getting the version
 * constant paired with the wrong IV still produces plausible-looking ciphertext, so the
 * failure shows up as an unreadable stream rather than an exception - worth pinning down
 * here instead of against a live server.
 */
class AgentCyphersTest {

    private static final InitializationVector SERVER_SEND_IV = InitializationVector.generateSend();
    private static final InitializationVector SERVER_RECV_IV = InitializationVector.generateReceive();

    private static ClientCyphers serverSide() {
        return ClientCyphers.of(SERVER_SEND_IV, SERVER_RECV_IV);
    }

    private static AgentCyphers agentSide() {
        return AgentCyphers.of(SERVER_SEND_IV, SERVER_RECV_IV);
    }

    private static byte[] encrypt(MapleAESOFB cypher, byte[] plaintext) {
        byte[] data = plaintext.clone();
        MapleCustomEncryption.encryptData(data);
        cypher.crypt(data);
        return data;
    }

    private static byte[] decrypt(MapleAESOFB cypher, byte[] ciphertext) {
        byte[] data = ciphertext.clone();
        cypher.crypt(data);
        MapleCustomEncryption.decryptData(data);
        return data;
    }

    @Test
    void agentReadsWhatTheServerWrites() {
        byte[] plaintext = "server to client".getBytes(StandardCharsets.US_ASCII);

        byte[] onTheWire = encrypt(serverSide().getSendCypher(), plaintext);
        byte[] received = decrypt(agentSide().getReceiveCypher(), onTheWire);

        assertArrayEquals(plaintext, received);
    }

    @Test
    void serverReadsWhatTheAgentWrites() {
        byte[] plaintext = "client to server".getBytes(StandardCharsets.US_ASCII);

        byte[] onTheWire = encrypt(agentSide().getSendCypher(), plaintext);
        byte[] received = decrypt(serverSide().getReceiveCypher(), onTheWire);

        assertArrayEquals(plaintext, received);
    }

    @Test
    void headersSurviveInBothDirections() {
        int fromServer = headerOf(serverSide().getSendCypher(), 42);
        assertTrue(agentSide().getReceiveCypher().isValidHeader(fromServer),
                "agent rejected a header the server produced");

        int fromAgent = headerOf(agentSide().getSendCypher(), 42);
        assertTrue(serverSide().getReceiveCypher().isValidHeader(fromAgent),
                "server would reject a header the agent produced");
    }

    /** The decoder reads the header as a big-endian int, so rebuild it the same way. */
    private static int headerOf(MapleAESOFB cypher, int packetLength) {
        byte[] header = cypher.getPacketHeader(packetLength);
        return ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
    }

    @Test
    void lengthRoundTrips() {
        int header = headerOf(serverSide().getSendCypher(), 1234);
        assertArrayEquals(new int[]{1234}, new int[]{MapleAESOFB.getPacketLength(header)});
    }
}
