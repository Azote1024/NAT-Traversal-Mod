package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.NatTraversalMod;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
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

    @Override
    public synchronized void start(int serverPort) {
        stop();
        establishedMarked.set(false);

        Optional<RelayEndpoint> endpoint = RelayEndpoint.parse(Config.quicPublishEndpoint(), "quic_publish_endpoint");
        if (endpoint.isEmpty()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_server phase=disabled room_name='{}' reason='invalid_quic_publish_endpoint'",
                    Config.roomName()
            );
            return;
        }

        File certFile = resolveTlsFile(Config.quicTlsCertFile());
        File keyFile = resolveTlsFile(Config.quicTlsKeyFile());
        if (!certFile.isFile() || !keyFile.isFile()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_server phase=disabled room_name='{}' reason='tls_file_missing' cert='{}' key='{}'",
                    Config.roomName(),
                    Config.quicTlsCertFile(),
                    Config.quicTlsKeyFile()
            );
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
            SupabaseQuicSessionClient.markHostPunchProbing(Config.roomName());
            String attemptId = currentAttemptId();
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_server phase=started room_name='{}' attempt_id='{}' bind='{}:{}' target='127.0.0.1:{}'",
                    Config.roomName(),
                    attemptId,
                    bindTarget.host(),
                    bindTarget.port(),
                    targetServerPort
            );
        } catch (Throwable exception) {
            String errorCode = classifyStartErrorCode(exception);
            String attemptId = currentAttemptId();
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] quic_server phase=start_failed room_name='{}' attempt_id='{}' error_code='{}'",
                    Config.roomName(),
                    attemptId,
                    errorCode,
                    exception
            );
            SupabaseQuicSessionClient.markHostPunchDown(Config.roomName(), errorCode);
            stop();
        }
    }

    private Channel bindUdpChannel(ChannelHandler codec, RelayEndpoint bindTarget) {
        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .handler(codec);

        InetSocketAddress requested = new InetSocketAddress(bindTarget.host(), bindTarget.port());
        InetSocketAddress preferred = preferBindableAddress(requested);
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] QUIC bind selection: publish='{}:{}', bind='{}:{}'",
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
                    "[nat-traversal-mod] QUIC bind failed on publish host '{}:{}'; retrying with wildcard '{}:{}'.",
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
                "[nat-traversal-mod] QUIC publish host '{}:{}' is not assigned locally; binding wildcard '{}:{}' instead.",
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

    private static String classifyStartErrorCode(Throwable throwable) {
        if (throwable == null) {
            return "start_failed";
        }
        if (isBindFailure(throwable)) {
            return "bind_failed";
        }
        if (throwable instanceof UnsatisfiedLinkError) {
            return "native_unavailable";
        }

        String text = throwable.toString().toLowerCase();
        if (text.contains("native") || text.contains("quiche")) {
            return "native_unavailable";
        }
        if (text.contains("cert") || text.contains("tls") || text.contains("ssl")) {
            return "tls_failed";
        }
        return "start_failed";
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
            String attemptId = currentAttemptId();
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_server phase=established room_name='{}' attempt_id='{}'",
                    Config.roomName(),
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
        candidates.add(userDir.resolve(rawPath));
        candidates.add(gameDir.resolve(rawPath));

        Path runStripped = stripRunPrefix(rawPath);
        if (runStripped != null) {
            candidates.add(userDir.resolve(runStripped));
            candidates.add(gameDir.resolve(runStripped));
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

    private static String currentAttemptId() {
        return SupabaseQuicSessionClient.fetchCurrentAttemptId(Config.roomName()).orElse("");
    }
}



