package agents.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.packet.InPacket;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * One TCP connection to a Cosmic login or channel server, speaking the v83 protocol as a
 * client would.
 *
 * A session is deliberately dumb: it connects, completes the handshake, sends packets it
 * is given and forwards packets it receives. It knows nothing about logging in, about
 * characters, or about the agent using it. Sessions are not reusable - a channel handoff
 * means a new session, as it does for a real client.
 */
public class MapleSession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MapleSession.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Receives packets on the netty event loop. Implementations must not block. */
    public interface Listener {
        void onPacket(InPacket packet);

        default void onDisconnected() {
        }
    }

    private final String host;
    private final int port;
    private final EventLoopGroup group;
    private final boolean ownsGroup;
    private volatile Listener listener;
    private volatile Channel channel;

    private MapleSession(String host, int port, EventLoopGroup group, boolean ownsGroup) {
        this.host = host;
        this.port = port;
        this.group = group;
        this.ownsGroup = ownsGroup;
    }

    /**
     * Connects and resolves once the handshake has completed, meaning the connection is
     * encrypted and ready for the first client packet.
     */
    public static CompletableFuture<MapleSession> connect(String host, int port, Listener listener) {
        return connect(host, port, listener, new NioEventLoopGroup(1), true);
    }

    public static CompletableFuture<MapleSession> connect(String host, int port, Listener listener,
                                                          EventLoopGroup group, boolean ownsGroup) {
        MapleSession session = new MapleSession(host, port, group, ownsGroup);
        session.listener = listener;

        CompletableFuture<Void> handshake = new CompletableFuture<>();
        CompletableFuture<MapleSession> connected = new CompletableFuture<>();

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        session.bind(ch);
                        ch.pipeline().addLast("Handshake", new HandshakeHandler(session, handshake));
                    }
                });

        bootstrap.connect(host, port).addListener(future -> {
            if (!future.isSuccess()) {
                handshake.completeExceptionally(future.cause());
            }
        });

        handshake.orTimeout(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        session.shutdownGroup();
                        connected.completeExceptionally(error);
                    } else {
                        log.debug("Connected to {}:{}", host, port);
                        connected.complete(session);
                    }
                });

        return connected;
    }

    void onPacket(InPacket packet) {
        Listener current = listener;
        if (current != null) {
            current.onPacket(packet);
        }
    }

    void onDisconnected() {
        log.debug("Disconnected from {}:{}", host, port);
        Listener current = listener;
        if (current != null) {
            current.onDisconnected();
        }
    }

    /** Swaps the listener, so a login session's packets can be routed to a new owner. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void send(Packet packet) {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            log.warn("Dropping packet to {}:{} - not connected", host, port);
            return;
        }
        ch.writeAndFlush(packet);
    }

    void bind(Channel channel) {
        this.channel = channel;
    }

    public boolean isConnected() {
        Channel ch = channel;
        return ch != null && ch.isActive();
    }

    @Override
    public void close() {
        Channel ch = channel;
        if (ch != null) {
            ch.close().awaitUninterruptibly(CONNECT_TIMEOUT.toMillis());
        }
        shutdownGroup();
    }

    private void shutdownGroup() {
        if (ownsGroup) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
