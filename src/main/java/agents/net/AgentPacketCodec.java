package agents.net;

import io.netty.channel.CombinedChannelDuplexHandler;
import net.encryption.PacketDecoder;
import net.encryption.PacketEncoder;

/**
 * Framing and encryption for the client side of the connection.
 *
 * Identical in behaviour to {@link net.encryption.PacketCodec}, which the server uses. It
 * exists only so the agent runtime can supply its own cypher pair without the server's
 * codec having to know that clients exist.
 */
class AgentPacketCodec extends CombinedChannelDuplexHandler<PacketDecoder, PacketEncoder> {
    AgentPacketCodec(AgentCyphers cyphers) {
        super(new PacketDecoder(cyphers.getReceiveCypher()), new PacketEncoder(cyphers.getSendCypher()));
    }
}
