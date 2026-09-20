package agents.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import net.encryption.InitializationVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Reads the server's unencrypted hello, then replaces itself with the encrypted codec.
 *
 * The hello is the only packet on the connection that is not encrypted, and it carries the
 * two IVs both sides need. Layout, all little endian:
 *
 * <pre>
 *   short  length of everything after this field (14)
 *   short  maple version
 *   short  length of the subversion string (1)
 *   byte   subversion, '1'
 *   byte[4] the server's receive IV - what we send with
 *   byte[4] the server's send IV    - what we receive with
 *   byte   locale
 * </pre>
 */
class HandshakeHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger log = LoggerFactory.getLogger(HandshakeHandler.class);
    private static final int HELLO_BODY_LENGTH = 14;

    private final CompletableFuture<Void> handshakeComplete;
    private final MapleSession session;

    HandshakeHandler(MapleSession session, CompletableFuture<Void> handshakeComplete) {
        this.session = session;
        this.handshakeComplete = handshakeComplete;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) {
        if (in.readableBytes() < 2 + HELLO_BODY_LENGTH) {
            // The hello is 16 bytes and arrives in one TCP segment in every case we have seen.
            // Bail loudly rather than silently half-reading it.
            handshakeComplete.completeExceptionally(
                    new IllegalStateException("Short hello: " + in.readableBytes() + " bytes"));
            ctx.close();
            return;
        }

        in.readShortLE();                           // body length
        short version = in.readShortLE();
        int subversionLength = in.readShortLE();
        in.skipBytes(subversionLength);
        byte[] serverRecvIv = new byte[4];
        byte[] serverSendIv = new byte[4];
        in.readBytes(serverRecvIv);
        in.readBytes(serverSendIv);
        in.readByte();                              // locale

        log.debug("Handshake from {}: version {}", ctx.channel().remoteAddress(), version);

        AgentCyphers cyphers = AgentCyphers.of(
                InitializationVector.of(serverSendIv),
                InitializationVector.of(serverRecvIv));

        ctx.pipeline().addAfter(ctx.name(), "PacketCodec", new AgentPacketCodec(cyphers));
        ctx.pipeline().addAfter("PacketCodec", "Session", new SessionHandler(session));
        ctx.pipeline().remove(this);

        handshakeComplete.complete(null);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        handshakeComplete.completeExceptionally(cause);
        ctx.close();
    }
}
