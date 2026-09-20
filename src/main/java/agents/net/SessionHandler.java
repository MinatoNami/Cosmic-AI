package agents.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands decrypted packets to the session's listener. Netty's event loop owns this thread,
 * so listeners must not block on it.
 */
class SessionHandler extends SimpleChannelInboundHandler<InPacket> {
    private static final Logger log = LoggerFactory.getLogger(SessionHandler.class);

    private final MapleSession session;

    SessionHandler(MapleSession session) {
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, InPacket packet) {
        session.onPacket(packet);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        session.onDisconnected();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Session error, closing connection", cause);
        ctx.close();
    }
}
