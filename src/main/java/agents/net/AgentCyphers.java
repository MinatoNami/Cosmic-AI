package agents.net;

import constants.net.ServerConstants;
import net.encryption.InitializationVector;
import net.encryption.MapleAESOFB;

/**
 * The client-side mirror of {@link net.encryption.ClientCyphers}.
 *
 * The server builds its pair as {@code send = AESOFB(sendIv, 0xFFFF - version)} and
 * {@code receive = AESOFB(recvIv, version)}, then hands both IVs to the client in the
 * unencrypted hello. A client therefore has to cross them over: what the server sends
 * with, we receive with, and vice versa. The version constants travel with the IV they
 * were paired with, so this is not simply the same factory with swapped arguments.
 */
public class AgentCyphers {
    private final MapleAESOFB send;
    private final MapleAESOFB receive;

    private AgentCyphers(MapleAESOFB send, MapleAESOFB receive) {
        this.send = send;
        this.receive = receive;
    }

    /**
     * Note the wire order: {@code PacketCreator.getHello} writes the server's <em>receive</em>
     * IV first and its <em>send</em> IV second, which is the opposite of the order here.
     *
     * @param serverSendIv the IV the server encrypts with - the second IV field in the hello
     * @param serverRecvIv the IV the server decrypts with - the first IV field in the hello
     */
    public static AgentCyphers of(InitializationVector serverSendIv, InitializationVector serverRecvIv) {
        MapleAESOFB send = new MapleAESOFB(serverRecvIv, ServerConstants.VERSION);
        MapleAESOFB receive = new MapleAESOFB(serverSendIv, (short) (0xFFFF - ServerConstants.VERSION));
        return new AgentCyphers(send, receive);
    }

    public MapleAESOFB getSendCypher() {
        return send;
    }

    public MapleAESOFB getReceiveCypher() {
        return receive;
    }
}
