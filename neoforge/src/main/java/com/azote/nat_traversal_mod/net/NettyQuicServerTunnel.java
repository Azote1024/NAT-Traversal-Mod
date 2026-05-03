package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.net.supabase.SupabaseQuicSessionClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NettyQuicServerTunnel implements QuicServerTunnel {
    private static final long MAX_DATA = 2_097_152L;

    private EventLoopGroup eventLoopGroup;
    private Channel udpChannel;
    private volatile boolean running;
    private volatile int targetServerPort;
    private final AtomicBoolean establishedMarked = new AtomicBoolean(false);
    private volatile boolean hostPunchAssistRunning;
    private Thread hostPunchAssistThread;
    private volatile String lastPunchAssistKey = "";
    private volatile long lastPunchAssistSentAtMillis;
    private volatile String hostObservedPublicEndpoint = "";
    private volatile String hostPublishedEndpoint = "";
    private volatile long hostObservedUpdatedAtMillis;
    private volatile boolean hostStunEnabled;
    private volatile String hostStunServer = "";
    private volatile int hostStunTimeoutMs;
    private volatile RelayEndpoint hostBindTarget;
    private volatile int hostPunchAssistPollMs = 300;
    private volatile int punchBurstCount = 3;
    private volatile int punchBurstIntervalMs = 40;
    private volatile int punchStaleAttemptMs = 30_000;

    @Override
    public synchronized void start(int serverPort) {
        stop();
        establishedMarked.set(false);

        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();

        File certFile = resolveTlsFile(runtimeConfig.quicTlsCertFile());
        File keyFile = resolveTlsFile(runtimeConfig.quicTlsKeyFile());
        if (!certFile.isFile() || !keyFile.isFile()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_server " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_DISABLED
                            + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' reason='tls_file_missing' cert='{}' key='{}'",
                    runtimeConfig.roomName(),
                    runtimeConfig.quicTlsCertFile(),
                    runtimeConfig.quicTlsKeyFile()
            );
            return;
        }

        RelayEndpoint bindTarget = resolveQuicBindTarget(runtimeConfig);
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
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            // No parent-channel handlers required for current tunnel mode.
                        }
                    })
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel streamChannel) {
                            handleIncomingStream(streamChannel);
                        }
                    })
                    .build();

            udpChannel = bindUdpChannel(codec, bindTarget);

            running = true;
            startHostPunchAssist(runtimeConfig.roomName(), runtimeConfig, bindTarget);
            SupabaseQuicSessionClient.markHostPunchProbing(runtimeConfig.roomName());
            String attemptId = currentAttemptId(runtimeConfig.roomName());
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_server " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_STARTED
                            + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}' bind='{}:{}' publish_fallback='{}' target='127.0.0.1:{}'",
                    runtimeConfig.roomName(),
                    attemptId,
                    bindTarget.host(),
                    bindTarget.port(),
                    hostPublishedEndpoint,
                    targetServerPort
            );
        } catch (Throwable exception) {
            String errorCode = QuicErrorCodes.classifyServerStartError(exception, isBindFailure(exception));
            String attemptId = currentAttemptId(runtimeConfig.roomName());
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] quic_server " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_START_FAILED
                            + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}' "
                            + QuicLogSchema.FIELD_ERROR_CODE + "='{}'",
                    runtimeConfig.roomName(),
                    attemptId,
                    errorCode,
                    exception
            );
            SupabaseQuicSessionClient.markHostPunchDown(runtimeConfig.roomName(), errorCode);
            stop();
        }
    }

    private static RelayEndpoint resolveQuicBindTarget(RuntimeConfigSnapshot runtimeConfig) {
        String bindHost = runtimeConfig.quicBindHost().isBlank()
                ? "0.0.0.0"
                : runtimeConfig.quicBindHost().trim();
        return new RelayEndpoint(bindHost, runtimeConfig.quicBindPort());
    }

    private Channel bindUdpChannel(ChannelHandler codec, RelayEndpoint bindTarget) {
        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .handler(codec);

        InetSocketAddress requested = new InetSocketAddress(bindTarget.host(), bindTarget.port());
        InetSocketAddress preferred = preferBindableAddress(requested);
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] QUIC bind selection: configured='{}:{}', bind='{}:{}'",
                bindTarget.host(),
                bindTarget.port(),
                preferred.getAddress() == null ? preferred.getHostString() : preferred.getAddress().getHostAddress(),
                preferred.getPort()
        );
        try {
            return bootstrap.bind(preferred).syncUninterruptibly().channel();
        } catch (RuntimeException exception) {
            if (!isBindFailure(exception) || isWildcardHost(bindTarget.host())) {
                throw exception;
            }

            InetSocketAddress wildcard = new InetSocketAddress("0.0.0.0", bindTarget.port());
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] QUIC bind failed on configured host '{}:{}'; retrying with wildcard '{}:{}'.",
                    bindTarget.host(),
                    bindTarget.port(),
                    wildcard.getAddress() == null ? wildcard.getHostString() : wildcard.getAddress().getHostAddress(),
                    wildcard.getPort(),
                    exception
            );
            return bootstrap.bind(wildcard).syncUninterruptibly().channel();
        }
    }

    private InetSocketAddress preferBindableAddress(InetSocketAddress requested) {
        String host = requested.getHostString();
        if (isWildcardHost(host)) {
            return requested;
        }
        if (isLocalInterfaceHost(host)) {
            return requested;
        }

        InetSocketAddress wildcard = new InetSocketAddress("0.0.0.0", requested.getPort());
        NatTraversalMod.LOGGER.warn(
                "[nat-traversal-mod] QUIC configured bind host '{}:{}' is not assigned locally; binding wildcard '{}:{}' instead.",
                host,
                requested.getPort(),
                wildcard.getAddress() == null ? wildcard.getHostString() : wildcard.getAddress().getHostAddress(),
                wildcard.getPort()
        );
        return wildcard;
    }

    private static boolean isLocalInterfaceHost(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (UnknownHostException | SocketException exception) {
            return false;
        }
    }

    private static boolean isBindFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BindException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("cannot assign requested address")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isWildcardHost(String host) {
        return "0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host);
    }

    @Override
    public synchronized void stop() {
        running = false;
        hostPunchAssistRunning = false;
        if (hostPunchAssistThread != null) {
            hostPunchAssistThread.interrupt();
            hostPunchAssistThread = null;
        }
        hostObservedPublicEndpoint = "";
        hostPublishedEndpoint = "";
        hostObservedUpdatedAtMillis = 0L;
        hostStunEnabled = false;
        hostStunServer = "";
        hostStunTimeoutMs = 0;
        hostBindTarget = null;
        hostPunchAssistPollMs = 300;
        punchBurstCount = 3;
        punchBurstIntervalMs = 40;
        punchStaleAttemptMs = 30_000;
        establishedMarked.set(false);
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        SupabaseQuicSessionClient.markHostPunchDown(runtimeConfig.roomName());

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
            RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
            SupabaseQuicSessionClient.markHostPunchEstablished(runtimeConfig.roomName());
            String attemptId = currentAttemptId(runtimeConfig.roomName());
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_server " + QuicLogSchema.FIELD_PHASE + "=" + QuicLogSchema.PHASE_ESTABLISHED
                            + " " + QuicLogSchema.FIELD_ROOM_NAME + "='{}' " + QuicLogSchema.FIELD_ATTEMPT_ID + "='{}'",
                    runtimeConfig.roomName(),
                    attemptId
            );
        }

        // Prevent early stream payload from being dropped before bridge handlers are installed.
        streamChannel.config().setAutoRead(false);
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
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] QUIC server stream bridge failed.", exception);
            RelayIoBridge.closeQuietly(localSocket);
            streamChannel.close().syncUninterruptibly();
        }
    }

    private static File resolveTlsFile(String configuredPath) {
        String trimmed = configuredPath == null ? "" : configuredPath.trim();
        if (trimmed.isEmpty()) {
            return new File("");
        }

        Path rawPath = Path.of(trimmed);
        if (rawPath.isAbsolute()) {
            return rawPath.toFile();
        }

        Set<Path> candidates = new LinkedHashSet<>();
        Path userDir = Path.of(System.getProperty("user.dir", "."));
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path configDir = FMLPaths.CONFIGDIR.get();
        candidates.add(userDir.resolve(rawPath));
        candidates.add(gameDir.resolve(rawPath));
        candidates.add(configDir.resolve(rawPath));
        candidates.add(gameDir.resolve("config").resolve(rawPath));

        Path runStripped = stripRunPrefix(rawPath);
        if (runStripped != null) {
            candidates.add(userDir.resolve(runStripped));
            candidates.add(gameDir.resolve(runStripped));
            candidates.add(configDir.resolve(runStripped));
            candidates.add(gameDir.resolve("config").resolve(runStripped));
        }

        Path configStripped = stripConfigPrefix(rawPath);
        if (configStripped != null) {
            candidates.add(configDir.resolve(configStripped));
            candidates.add(gameDir.resolve("config").resolve(configStripped));
            candidates.add(userDir.resolve(configStripped));
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toFile();
            }
        }

        List<Path> attempted = new ArrayList<>(candidates);
        NatTraversalMod.LOGGER.debug("[nat-traversal-mod] TLS path candidates checked: {}", attempted);
        return userDir.resolve(rawPath).toFile();
    }

    private static Path stripRunPrefix(Path path) {
        if (path.getNameCount() < 2) {
            return null;
        }
        String first = path.getName(0).toString();
        if (!"run".equalsIgnoreCase(first)) {
            return null;
        }
        return path.subpath(1, path.getNameCount());
    }

    private static Path stripConfigPrefix(Path path) {
        if (path.getNameCount() < 2) {
            return null;
        }
        String first = path.getName(0).toString();
        if (!"config".equalsIgnoreCase(first)) {
            return null;
        }
        return path.subpath(1, path.getNameCount());
    }

    private static String currentAttemptId(String roomName) {
        return SupabaseQuicSessionClient.fetchCurrentAttemptId(roomName).orElse("");
    }

    private synchronized void startHostPunchAssist(String roomName, RuntimeConfigSnapshot runtimeConfig, RelayEndpoint bindTarget) {
        hostPunchAssistRunning = true;
        lastPunchAssistKey = "";
        lastPunchAssistSentAtMillis = 0L;
        hostStunEnabled = runtimeConfig.stunEnabled();
        hostStunServer = runtimeConfig.stunServer();
        hostStunTimeoutMs = runtimeConfig.stunTimeoutMs();
        hostBindTarget = bindTarget;
        hostPunchAssistPollMs = runtimeConfig.punchHostAssistPollMs();
        punchBurstCount = runtimeConfig.punchBurstCount();
        punchBurstIntervalMs = runtimeConfig.punchBurstIntervalMs();
        punchStaleAttemptMs = runtimeConfig.punchStaleAttemptMs();
        hostPublishedEndpoint = fallbackHostPublicEndpoint(runtimeConfig, bindTarget);
        hostObservedPublicEndpoint = resolveHostObservedPublicEndpoint(runtimeConfig, bindTarget, udpChannel);
        hostObservedUpdatedAtMillis = System.currentTimeMillis();
        SupabaseQuicSessionClient.updateHostPublicEndpoint(roomName, hostObservedPublicEndpoint);
        hostPunchAssistThread = new Thread(() -> runHostPunchAssistLoop(roomName), "nat-quic-host-punch-assist");
        hostPunchAssistThread.setDaemon(true);
        hostPunchAssistThread.start();
    }

    private void runHostPunchAssistLoop(String roomName) {
        while (running && hostPunchAssistRunning) {
            try {
                Optional<SupabaseQuicSessionClient.PeerPunchSyncInfo> syncInfo =
                        SupabaseQuicSessionClient.fetchLatestPeerPunchSyncInfo(roomName);
                if (syncInfo.isPresent()) {
                    maybeSendHostPunchAssist(roomName, syncInfo.get());
                }
                Thread.sleep(hostPunchAssistPollMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                NatTraversalMod.LOGGER.warn(
                        "[nat-traversal-mod] quic_punch phase=host_assist_loop_error room_name='{}'",
                        roomName,
                        exception
                );
                try {
                    Thread.sleep(hostPunchAssistPollMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void maybeSendHostPunchAssist(String roomName, SupabaseQuicSessionClient.PeerPunchSyncInfo syncInfo) {
        String clientEndpointRaw = syncInfo.clientPublicEndpoint();
        String clientKey = syncInfo.clientKey() == null ? "" : syncInfo.clientKey();
        String attemptId = syncInfo.attemptId() == null ? "" : syncInfo.attemptId();
        if (clientKey.isBlank() || attemptId.isBlank()) {
            return;
        }

        if (isPeerAttemptStale(syncInfo, punchStaleAttemptMs)) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_punch phase=host_assist_skip error_code=stale_peer_attempt room_name='{}' attempt_id='{}' updated_at='{}' stale_ms={}",
                    roomName,
                    attemptId,
                    syncInfo.updatedAt(),
                    punchStaleAttemptMs
            );
            return;
        }

        if (isPunchWindowNotOpenedYet(syncInfo)) {
            return;
        }

        refreshHostObservedEndpointIfStale(roomName, syncInfo);

        Optional<RelayEndpoint> parsedClientEndpoint = RelayEndpoint.parse(clientEndpointRaw, "client_public_endpoint");
        if (parsedClientEndpoint.isEmpty()) {
            SupabaseQuicSessionClient.upsertPeerAttemptSync(
                    roomName,
                    clientKey,
                    attemptId,
                    clientEndpointRaw,
                    hostObservedPublicEndpoint,
                    syncInfo.punchSyncToken(),
                    syncInfo.punchWindowOpenedAt(),
                    syncInfo.punchWindowMs(),
                    withHostEndpointContext("host_assist_invalid_client_endpoint")
            );
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_punch phase=host_assist_skip error_code=invalid_client_public_endpoint room_name='{}' attempt_id='{}' endpoint='{}'",
                    roomName,
                    attemptId,
                    clientEndpointRaw
            );
            return;
        }

        String assistKey = attemptId + "|" + syncInfo.punchSyncToken() + "|" + syncInfo.punchWindowOpenedAt();
        long nowMillis = System.currentTimeMillis();
        long dedupeWindowMs = Math.max(50L, Math.min(450L, hostPunchAssistPollMs * 2L));
        if (assistKey.equals(lastPunchAssistKey) && (nowMillis - lastPunchAssistSentAtMillis) < dedupeWindowMs) {
            return;
        }

        if (isPunchWindowExpired(syncInfo)) {
            SupabaseQuicSessionClient.upsertPeerAttemptSync(
                    roomName,
                    clientKey,
                    attemptId,
                    clientEndpointRaw,
                    hostObservedPublicEndpoint,
                    syncInfo.punchSyncToken(),
                    syncInfo.punchWindowOpenedAt(),
                    syncInfo.punchWindowMs(),
                    QuicErrorCodes.SYNC_MISS
            );
            lastPunchAssistKey = assistKey;
            lastPunchAssistSentAtMillis = nowMillis;
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_punch phase=host_assist_skip error_code=sync_miss room_name='{}' attempt_id='{}' window_opened_at='{}' window_ms={}",
                    roomName,
                    attemptId,
                    syncInfo.punchWindowOpenedAt(),
                    syncInfo.punchWindowMs()
            );
            return;
        }

        String payload = "NAT-PUNCH-HOST " + roomName + " " + attemptId + " " + syncInfo.punchSyncToken();
        sendHostPunchBurst(parsedClientEndpoint.get(), payload);
        SupabaseQuicSessionClient.upsertPeerAttemptSync(
                roomName,
                clientKey,
                attemptId,
                clientEndpointRaw,
                hostObservedPublicEndpoint,
                syncInfo.punchSyncToken(),
                syncInfo.punchWindowOpenedAt(),
                syncInfo.punchWindowMs(),
                withHostEndpointContext("host_assist_sent")
        );
        lastPunchAssistKey = assistKey;
        lastPunchAssistSentAtMillis = nowMillis;
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] quic_punch phase=host_assist_sent room_name='{}' attempt_id='{}' endpoint='{}:{}' host_published_endpoint='{}' host_observed_public_endpoint='{}' window_ms={} burst_count={}",
                roomName,
                attemptId,
                parsedClientEndpoint.get().host(),
                parsedClientEndpoint.get().port(),
                hostPublishedEndpoint,
                hostObservedPublicEndpoint,
                syncInfo.punchWindowMs(),
                punchBurstCount
        );
    }

    private static boolean isPunchWindowExpired(SupabaseQuicSessionClient.PeerPunchSyncInfo syncInfo) {
        if (syncInfo.punchWindowOpenedAt() == null || syncInfo.punchWindowOpenedAt().isBlank()) {
            return false;
        }
        if (syncInfo.punchWindowMs() <= 0) {
            return false;
        }
        try {
            Instant openedAt = Instant.parse(syncInfo.punchWindowOpenedAt());
            Instant deadline = openedAt.plusMillis(syncInfo.punchWindowMs());
            return Instant.now().isAfter(deadline);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean isPeerAttemptStale(SupabaseQuicSessionClient.PeerPunchSyncInfo syncInfo, int staleAttemptMs) {
        if (staleAttemptMs <= 0) {
            return false;
        }
        String updatedAt = syncInfo.updatedAt();
        if (updatedAt == null || updatedAt.isBlank()) {
            return false;
        }
        try {
            Instant updated = Instant.parse(updatedAt);
            return Instant.now().isAfter(updated.plusMillis(staleAttemptMs));
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean isPunchWindowNotOpenedYet(SupabaseQuicSessionClient.PeerPunchSyncInfo syncInfo) {
        if (syncInfo.punchWindowOpenedAt() == null || syncInfo.punchWindowOpenedAt().isBlank()) {
            return false;
        }
        try {
            Instant openedAt = Instant.parse(syncInfo.punchWindowOpenedAt());
            return Instant.now().isBefore(openedAt);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private void refreshHostObservedEndpointIfStale(String roomName, SupabaseQuicSessionClient.PeerPunchSyncInfo syncInfo) {
        if (!hostStunEnabled || hostBindTarget == null || hostStunServer.isBlank()) {
            return;
        }
        if (syncInfo.punchWindowOpenedAt() == null || syncInfo.punchWindowOpenedAt().isBlank()) {
            return;
        }

        long windowOpenedAtMillis;
        try {
            windowOpenedAtMillis = Instant.parse(syncInfo.punchWindowOpenedAt()).toEpochMilli();
        } catch (DateTimeParseException exception) {
            return;
        }

        // If observed endpoint was captured long before this sync window, refresh it.
        if (hostObservedUpdatedAtMillis + 400L >= windowOpenedAtMillis) {
            return;
        }

        RuntimeConfigSnapshot snapshot = RuntimeConfigLoader.load();
        String refreshed = resolveHostObservedPublicEndpoint(snapshot, hostBindTarget, udpChannel);
        if (!refreshed.isBlank() && !refreshed.equals(hostObservedPublicEndpoint)) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_punch phase=host_observed_refresh old='{}' new='{}'",
                    hostObservedPublicEndpoint,
                    refreshed
            );
            hostObservedPublicEndpoint = refreshed;
            SupabaseQuicSessionClient.updateHostPublicEndpoint(roomName, hostObservedPublicEndpoint);
        }
        hostObservedUpdatedAtMillis = System.currentTimeMillis();
    }

    private static String resolveHostObservedPublicEndpoint(RuntimeConfigSnapshot runtimeConfig, RelayEndpoint bindTarget, Channel udpChannel) {
        String fallback = fallbackHostPublicEndpoint(runtimeConfig, bindTarget);
        if (!runtimeConfig.stunEnabled()) {
            return fallback;
        }

        Optional<String> socketProbedEndpoint = QuicSocketStunProbe.resolvePublicEndpoint(
                udpChannel,
                runtimeConfig.stunServer(),
                runtimeConfig.stunTimeoutMs()
        );
        if (socketProbedEndpoint.isPresent()) {
            Optional<RelayEndpoint> parsed = RelayEndpoint.parse(socketProbedEndpoint.get(), "host_public_endpoint_socket");
            if (parsed.isPresent()) {
                String resolved = parsed.get().host() + ":" + parsed.get().port();
                NatTraversalMod.LOGGER.info(
                        "[nat-traversal-mod] quic_punch phase=host_public_endpoint_resolved source='socket_stun' endpoint='{}'",
                        resolved
                );
                return resolved;
            }
        }

        Optional<String> fallbackStunEndpoint = StunClient.resolvePublicEndpoint(runtimeConfig.stunServer(), runtimeConfig.stunTimeoutMs());
        if (fallbackStunEndpoint.isEmpty()) {
            return fallback;
        }
        Optional<RelayEndpoint> parsedFallback = RelayEndpoint.parse(fallbackStunEndpoint.get(), "host_public_endpoint_fallback");
        if (parsedFallback.isEmpty()) {
            return fallback;
        }
        String resolved = parsedFallback.get().host() + ":" + parsedFallback.get().port();
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] quic_punch phase=host_public_endpoint_resolved source='standalone_stun' endpoint='{}'",
                resolved
        );
        return resolved;
    }

    private static String fallbackHostPublicEndpoint(RuntimeConfigSnapshot runtimeConfig, RelayEndpoint bindTarget) {
        if (!runtimeConfig.publishHostIp().isBlank()) {
            return runtimeConfig.publishHostIp() + ":" + bindTarget.port();
        }
        if (!isWildcardHost(bindTarget.host())) {
            return bindTarget.host() + ":" + bindTarget.port();
        }
        return "";
    }

    private String withHostEndpointContext(String baseTransition) {
        return baseTransition
                + "|published=" + hostPublishedEndpoint
                + "|observed=" + hostObservedPublicEndpoint;
    }

    private void sendHostPunchBurst(RelayEndpoint endpoint, String payload) {
        if (udpChannel == null) {
            return;
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        int count = Math.max(1, punchBurstCount);
        int intervalMs = Math.max(0, punchBurstIntervalMs);
        for (int i = 0; i < count; i++) {
            DatagramPacket packet = new DatagramPacket(Unpooled.wrappedBuffer(bytes), new InetSocketAddress(endpoint.host(), endpoint.port()));
            udpChannel.writeAndFlush(packet);
            if (intervalMs == 0 || i + 1 >= count) {
                continue;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}



