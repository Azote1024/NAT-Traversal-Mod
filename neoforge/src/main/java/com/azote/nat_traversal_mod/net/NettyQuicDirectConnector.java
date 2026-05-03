package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.QuicTlsMode;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.net.supabase.SupabaseQuicSessionClient;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

        sendPreConnectPunch(udpChannel, address, roomName, normalizedAttemptId, runtimeConfig);

        io.netty.util.concurrent.Future<QuicChannel> quicConnectFuture = QuicChannel.newBootstrap(udpChannel)
                .streamHandler(new ChannelInboundHandlerAdapter())
                .remoteAddress(address)
                .connect();

        boolean connectCompleted = quicConnectFuture.awaitUninterruptibly(CONNECT_TIMEOUT_MILLIS);
        if (!connectCompleted || !quicConnectFuture.isSuccess()) {
            String errorCode = !connectCompleted
                    ? QuicErrorCodes.NO_RETURN_TRAFFIC
                    : QuicErrorCodes.classifyDirectConnectError(quicConnectFuture.cause());
            SupabaseQuicSessionClient.upsertPeerAttempt(
                    roomName,
                    QuicP2pManager.clientKey(),
                    normalizedAttemptId,
                    "unknown",
                    "quic_try",
                    "down",
                    errorCode,
                    false
            );
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
            logAttemptOutcome(roomName, normalizedAttemptId, "quic_direct_failed", errorCode);
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
            SupabaseQuicSessionClient.upsertPeerAttempt(
                    roomName,
                    QuicP2pManager.clientKey(),
                    normalizedAttemptId,
                    "unknown",
                    "quic_try",
                    "down",
                    errorCode,
                    false
            );
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
            logAttemptOutcome(roomName, normalizedAttemptId, "quic_direct_failed", errorCode);
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
        SupabaseQuicSessionClient.upsertPeerAttempt(
                roomName,
                QuicP2pManager.clientKey(),
                normalizedAttemptId,
                "unknown",
                "quic_try",
                "established",
                "",
                false
        );
        logAttemptOutcome(roomName, normalizedAttemptId, "quic_direct_success", "");

        return Optional.of(stream.newSucceededFuture());
    }

    private static void sendPreConnectPunch(
            io.netty.channel.Channel udpChannel,
            InetSocketAddress address,
            String roomName,
            String attemptId,
            RuntimeConfigSnapshot runtimeConfig
    ) {
        String clientKey = QuicP2pManager.clientKey();
        Optional<SupabaseQuicSessionClient.PeerPunchSyncInfo> syncInfo =
                SupabaseQuicSessionClient.fetchPeerPunchSyncInfo(roomName, clientKey, attemptId);

        String clientPublicEndpoint = syncInfo.map(SupabaseQuicSessionClient.PeerPunchSyncInfo::clientPublicEndpoint).orElse("");
        if (runtimeConfig.stunEnabled()) {
            Optional<String> probedEndpoint = QuicSocketStunProbe.resolvePublicEndpoint(
                    udpChannel,
                    runtimeConfig.stunServer(),
                    runtimeConfig.stunTimeoutMs()
            );
            if (probedEndpoint.isPresent()) {
                clientPublicEndpoint = probedEndpoint.get();
                if (syncInfo.isPresent()) {
                    SupabaseQuicSessionClient.PeerPunchSyncInfo value = syncInfo.get();
                    SupabaseQuicSessionClient.upsertPeerAttemptSync(
                            roomName,
                            clientKey,
                            attemptId,
                            clientPublicEndpoint,
                            "",
                            value.punchSyncToken(),
                            value.punchWindowOpenedAt(),
                            value.punchWindowMs(),
                            "client_socket_public_endpoint_updated"
                    );
                    syncInfo = Optional.of(new SupabaseQuicSessionClient.PeerPunchSyncInfo(
                            value.clientKey(),
                            clientPublicEndpoint,
                            value.attemptId(),
                            value.punchSyncToken(),
                            value.punchWindowOpenedAt(),
                            value.punchWindowMs(),
                            value.lastTransition(),
                            Instant.now().toString()
                    ));
                }
            }
        }

        if (syncInfo.isPresent()) {
            waitUntilPunchWindow(syncInfo.get());
        }

        String syncToken = syncInfo.map(SupabaseQuicSessionClient.PeerPunchSyncInfo::punchSyncToken).orElse("");
        String payload = "NAT-PUNCH " + roomName + " " + attemptId + " " + syncToken + " preconnect";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        int burstCount = Math.max(1, runtimeConfig.punchBurstCount());
        int burstIntervalMs = Math.max(0, runtimeConfig.punchBurstIntervalMs());
        for (int i = 0; i < burstCount; i++) {
            DatagramPacket packet = new DatagramPacket(Unpooled.wrappedBuffer(bytes), address);
            udpChannel.writeAndFlush(packet).syncUninterruptibly();
            if (burstIntervalMs == 0 || i + 1 >= burstCount) {
                continue;
            }
            try {
                Thread.sleep(burstIntervalMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        QuicAttemptRecorder.markPunchSent(roomName, clientKey, attemptId);
        if (syncInfo.isPresent()) {
            SupabaseQuicSessionClient.PeerPunchSyncInfo value = syncInfo.get();
            SupabaseQuicSessionClient.upsertPeerAttemptSync(
                    roomName,
                    clientKey,
                    attemptId,
                    clientPublicEndpoint,
                    "",
                    value.punchSyncToken(),
                    value.punchWindowOpenedAt(),
                    value.punchWindowMs(),
                    "punch_sent_socket_shared"
            );
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] quic_direct " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_PRE_PUNCH_SENT
                        + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}' "
                        + QuicLogSchema.FIELD_TARGET + "='{}:{}' sync_token_present={} count={}",
                roomName,
                attemptId,
                address.getHostString(),
                address.getPort(),
                !syncToken.isBlank(),
                burstCount
        );
    }

    private static void logAttemptOutcome(String roomName, String attemptId, String outcome, String errorCode) {
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] quic_attempt outcome='{}' room_name='{}' attempt_id='{}' error_code='{}'",
                outcome,
                roomName,
                attemptId,
                errorCode == null ? "" : errorCode
        );
    }

    private static void waitUntilPunchWindow(SupabaseQuicSessionClient.PeerPunchSyncInfo syncInfo) {
        String openedAtValue = syncInfo.punchWindowOpenedAt();
        if (openedAtValue == null || openedAtValue.isBlank()) {
            return;
        }
        try {
            Instant openedAt = Instant.parse(openedAtValue);
            long waitMillis = openedAt.toEpochMilli() - System.currentTimeMillis();
            if (waitMillis > 0L) {
                Thread.sleep(Math.min(waitMillis, 1000L));
            }
        } catch (DateTimeParseException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
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


