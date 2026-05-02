package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NettyQuicServerTunnel implements QuicServerTunnel {
    private static final long MAX_DATA = 2_097_152L;

    private EventLoopGroup eventLoopGroup;
    private Channel udpChannel;
    private volatile boolean running;
    private volatile int targetServerPort;
    private final AtomicBoolean establishedMarked = new AtomicBoolean(false);

    @Override
    public synchronized void start(int serverPort) {
        stop();
        establishedMarked.set(false);

        Optional<RelayEndpoint> endpoint = RelayEndpoint.parse(Config.quicPublishEndpoint(), "quic_publish_endpoint");
        if (endpoint.isEmpty()) {
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] QUIC server tunnel disabled: invalid quic_publish_endpoint.");
            return;
        }

        File certFile = new File(Config.quicTlsCertFile());
        File keyFile = new File(Config.quicTlsKeyFile());
        if (!certFile.isFile() || !keyFile.isFile()) {
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] QUIC server tunnel disabled: TLS cert/key file is missing.");
            return;
        }

        RelayEndpoint bindTarget = endpoint.get();
        targetServerPort = serverPort;
        eventLoopGroup = new NioEventLoopGroup(1);

        try {
            var sslContext = QuicSslContextBuilder.forServer(keyFile, null, certFile)
                    .applicationProtocols("minecraft")
                    .build();

            var codec = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(10, TimeUnit.SECONDS)
                    .initialMaxData(MAX_DATA)
                    .initialMaxStreamsBidirectional(16)
                    .initialMaxStreamDataBidirectionalRemote(MAX_DATA)
                    .congestionControlAlgorithm(QuicCongestionControlAlgorithm.BBR)
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                    .handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel streamChannel) {
                            handleIncomingStream(streamChannel);
                        }
                    })
                    .build();

            udpChannel = new Bootstrap()
                    .group(eventLoopGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(new InetSocketAddress(bindTarget.host(), bindTarget.port()))
                    .syncUninterruptibly()
                    .channel();

            running = true;
            SupabaseQuicSessionClient.markHostPunchProbing(Config.roomName());
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] QUIC server tunnel started. bind='{}:{}', target='127.0.0.1:{}'",
                    bindTarget.host(),
                    bindTarget.port(),
                    targetServerPort
            );
        } catch (RuntimeException exception) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] QUIC server tunnel start failed.", exception);
            SupabaseQuicSessionClient.markHostPunchDown(Config.roomName());
            stop();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        establishedMarked.set(false);
        SupabaseQuicSessionClient.markHostPunchDown(Config.roomName());

        if (udpChannel != null) {
            udpChannel.close().syncUninterruptibly();
            udpChannel = null;
        }

        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
            eventLoopGroup = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void handleIncomingStream(QuicStreamChannel streamChannel) {
        if (establishedMarked.compareAndSet(false, true)) {
            SupabaseQuicSessionClient.markHostPunchEstablished(Config.roomName());
        }
        Thread thread = new Thread(() -> bridgeStreamToLocalServer(streamChannel), "nat-quic-server-session");
        thread.setDaemon(true);
        thread.start();
    }

    private void bridgeStreamToLocalServer(QuicStreamChannel streamChannel) {
        Socket localSocket = new Socket();
        try {
            localSocket.connect(new InetSocketAddress("127.0.0.1", targetServerPort), 3000);
            QuicSocketBridge.bridge(localSocket, streamChannel, "nat-quic-server-writer");
        } catch (IOException exception) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] QUIC server stream bridge failed.", exception);
            RelayIoBridge.closeQuietly(localSocket);
            streamChannel.close().syncUninterruptibly();
        }
    }
}


