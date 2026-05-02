package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.QuicTlsMode;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.FingerprintTrustManagerFactory;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.channel.socket.DatagramPacket;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class NettyQuicDirectConnector implements QuicDirectConnector {
    private static final long MAX_DATA = 2_097_152L;
    private static final long CONNECT_TIMEOUT_MILLIS = 3000L;

    @Override
    public boolean isOperational() {
        return true;
    }

    @Override
    public Optional<ChannelFuture> connect(InetSocketAddress address, boolean useEpoll, Connection connection, String attemptId) {
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        String roomName = runtimeConfig.roomName();
        String normalizedAttemptId = attemptId == null ? "" : attemptId;
        boolean useNativeEpoll = Epoll.isAvailable() && useEpoll;
        EventLoopGroup ioGroup = useNativeEpoll ? Connection.NETWORK_EPOLL_WORKER_GROUP.get() : Connection.NETWORK_WORKER_GROUP.get();

        QuicSslContextBuilder sslBuilder = QuicSslContextBuilder.forClient().applicationProtocols("minecraft");
        applyClientTlsMode(runtimeConfig, sslBuilder);

        var codec = new QuicClientCodecBuilder()
                .sslContext(sslBuilder.build())
                .maxIdleTimeout(8, TimeUnit.SECONDS)
                .initialMaxData(MAX_DATA)
                .initialMaxStreamDataBidirectionalLocal(MAX_DATA)
                .congestionControlAlgorithm(QuicCongestionControlAlgorithm.BBR)
                .build();

        var udpChannel = new Bootstrap()
                .group(ioGroup)
                .channel(useNativeEpoll ? EpollDatagramChannel.class : NioDatagramChannel.class)
                .handler(codec)
                .bind(0)
                .syncUninterruptibly()
                .channel();

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] quic_direct " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_DIAL_START
                        + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}' "
                        + QuicLogSchema.FIELD_TARGET + "='{}:{}' epoll={} tls_mode='{}'",
                roomName,
                normalizedAttemptId,
                address.getHostString(),
                address.getPort(),
                useNativeEpoll,
                toDisplayTlsMode(runtimeConfig.quicTlsMode())
        );

        sendPreConnectPunch(udpChannel, address, roomName, normalizedAttemptId);

        io.netty.util.concurrent.Future<QuicChannel> quicConnectFuture = QuicChannel.newBootstrap(udpChannel)
                .streamHandler(new ChannelInboundHandlerAdapter())
                .remoteAddress(address)
                .connect();

        if (!quicConnectFuture.awaitUninterruptibly(CONNECT_TIMEOUT_MILLIS) || !quicConnectFuture.isSuccess()) {
            String errorCode = QuicErrorCodes.classifyDirectConnectError(quicConnectFuture.cause());
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_direct " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_CHANNEL_CONNECT_FAILED
                            + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}' "
                            + QuicLogSchema.FIELD_TARGET + "='{}:{}' " + QuicLogSchema.FIELD_ERROR_CODE + "='{}'",
                    roomName,
                    normalizedAttemptId,
                    address.getHostString(),
                    address.getPort(),
                    errorCode,
                    quicConnectFuture.cause()
            );
            udpChannel.close().syncUninterruptibly();
            return Optional.empty();
        }

        QuicChannel quicChannel = quicConnectFuture.getNow();

        if (quicChannel == null) {
            udpChannel.close().syncUninterruptibly();
            return Optional.empty();
        }

        io.netty.util.concurrent.Future<QuicStreamChannel> streamFuture = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel channel) {
                Connection.configureSerialization(channel.pipeline(), PacketFlow.CLIENTBOUND, false, null);
                connection.configurePacketHandler(channel.pipeline());
            }
        });

        if (!streamFuture.awaitUninterruptibly(CONNECT_TIMEOUT_MILLIS) || !streamFuture.isSuccess()) {
            String errorCode = QuicErrorCodes.classifyDirectStreamError(streamFuture.cause());
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_direct " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_STREAM_CREATE_FAILED
                            + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}' "
                            + QuicLogSchema.FIELD_TARGET + "='{}:{}' " + QuicLogSchema.FIELD_ERROR_CODE + "='{}'",
                    roomName,
                    normalizedAttemptId,
                    address.getHostString(),
                    address.getPort(),
                    errorCode,
                    streamFuture.cause()
            );
            quicChannel.close().syncUninterruptibly();
            udpChannel.close().syncUninterruptibly();
            return Optional.empty();
        }

        QuicStreamChannel stream = streamFuture.getNow();

        if (stream == null) {
            quicChannel.close().syncUninterruptibly();
            udpChannel.close().syncUninterruptibly();
            return Optional.empty();
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] quic_direct " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_STREAM_READY
                        + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}' "
                        + QuicLogSchema.FIELD_TARGET + "='{}:{}'",
                roomName,
                normalizedAttemptId,
                address.getHostString(),
                address.getPort()
        );

        return Optional.of(stream.newSucceededFuture());
    }

    private static void sendPreConnectPunch(io.netty.channel.Channel udpChannel, InetSocketAddress address, String roomName, String attemptId) {
        String payload = "NAT-PUNCH " + roomName + " " + attemptId + " preconnect";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            DatagramPacket packet = new DatagramPacket(Unpooled.wrappedBuffer(bytes), address);
            udpChannel.writeAndFlush(packet).syncUninterruptibly();
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] quic_direct " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_PRE_PUNCH_SENT
                        + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}' "
                        + QuicLogSchema.FIELD_TARGET + "='{}:{}' count=3",
                roomName,
                attemptId,
                address.getHostString(),
                address.getPort()
        );
    }

    private static void applyClientTlsMode(RuntimeConfigSnapshot runtimeConfig, QuicSslContextBuilder sslBuilder) {
        if (runtimeConfig.quicTlsMode() == QuicTlsMode.INSECURE_TRUST_ALL) {
            sslBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            return;
        }

        String fingerprint = runtimeConfig.quicCertFingerprintSha256();
        if (!fingerprint.isBlank()) {
            sslBuilder.trustManager(new FingerprintTrustManagerFactory(fingerprint));
        }
    }

    private static String toDisplayTlsMode(QuicTlsMode mode) {
        return mode == QuicTlsMode.INSECURE_TRUST_ALL ? "insecure_trust_all" : "ca_or_pinned";
    }
}


