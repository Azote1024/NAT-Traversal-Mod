package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class NettyQuicTransport implements QuicTransport {
    private static final long MAX_DATA = 2_097_152L;
    private static final EventLoopGroup QUIC_IO_GROUP = new NioEventLoopGroup(1);
    private volatile boolean localTunnelStarted;
    private volatile RelayEndpoint currentEndpoint;

    @Override
    public boolean isOperational() {
        return true;
    }

    @Override
    public Optional<ResolvedTarget> tryActivate(RelayEndpoint endpoint, String roomName) {
        if (!testConnectivity(endpoint)) {
            return Optional.empty();
        }

        currentEndpoint = endpoint;
        if (!localTunnelStarted) {
            startLocalTunnel();
        }

        if (!localTunnelStarted) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedTarget("127.0.0.1", Config.quicClientLocalPort()));
    }

    private synchronized void startLocalTunnel() {
        if (localTunnelStarted) {
            return;
        }

        int localPort = Config.quicClientLocalPort();
        Thread thread = new Thread(() -> runLocalTunnelLoop(localPort), "nat-quic-client-tunnel");
        thread.setDaemon(true);
        thread.start();
        localTunnelStarted = true;
        Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] QUIC client tunnel started on 127.0.0.1:{}", localPort);
    }

    private void runLocalTunnelLoop(int localPort) {
        try (ServerSocket serverSocket = new ServerSocket(localPort)) {
            while (true) {
                Socket localSocket = serverSocket.accept();
                Thread thread = new Thread(() -> handleLocalSession(localSocket), "nat-quic-client-session");
                thread.setDaemon(true);
                thread.start();
            }
        } catch (IOException exception) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] QUIC client tunnel stopped.", exception);
            localTunnelStarted = false;
        }
    }

    private void handleLocalSession(Socket localSocket) {
        RelayEndpoint endpoint = currentEndpoint;
        if (endpoint == null) {
            RelayIoBridge.closeQuietly(localSocket);
            return;
        }

        try {
            QuicStreamChannel stream = openQuicStream(endpoint);
            QuicSocketBridge.bridge(localSocket, stream, "nat-quic-client-writer");
        } catch (RuntimeException | IOException exception) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] QUIC client session failed.", exception);
            RelayIoBridge.closeQuietly(localSocket);
        }
    }

    private boolean testConnectivity(RelayEndpoint endpoint) {
        try {
            QuicStreamChannel stream = openQuicStream(endpoint);
            closeStreamHierarchy(stream);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private QuicStreamChannel openQuicStream(RelayEndpoint endpoint) {
        QuicSslContextBuilder sslContextBuilder = QuicSslContextBuilder.forClient()
                .applicationProtocols("minecraft");

        if ("insecure_trust_all".equals(Config.quicTlsMode())) {
            sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] QUIC TLS mode uses insecure trust-all. room route security is reduced.");
        } else {
            X509TrustManager trustManager = createCaOrPinnedTrustManager(Config.quicCertFingerprintSha256());
            sslContextBuilder.trustManager(trustManager);
        }

        var codec = new QuicClientCodecBuilder()
                .sslContext(sslContextBuilder.build())
                .maxIdleTimeout(5, TimeUnit.SECONDS)
                .initialMaxData(MAX_DATA)
                .initialMaxStreamDataBidirectionalLocal(MAX_DATA)
                .congestionControlAlgorithm(QuicCongestionControlAlgorithm.BBR)
                .build();

        Channel udpChannel = new Bootstrap()
                .group(QUIC_IO_GROUP)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(0)
                .syncUninterruptibly()
                .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(udpChannel)
                .streamHandler(new ChannelInboundHandlerAdapter())
                .remoteAddress(new InetSocketAddress(endpoint.host(), endpoint.port()))
                .connect()
                .getNow();

        if (quicChannel == null) {
            udpChannel.close().syncUninterruptibly();
            throw new IllegalStateException("QUIC connect failed");
        }

        QuicStreamChannel stream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel ch) {
            }
        }).getNow();

        if (stream == null) {
            quicChannel.close().syncUninterruptibly();
            udpChannel.close().syncUninterruptibly();
            throw new IllegalStateException("QUIC stream create failed");
        }

        return stream;
    }

    private static X509TrustManager createCaOrPinnedTrustManager(String pinnedFingerprint) {
        List<X509TrustManager> defaults = loadDefaultTrustManagers();
        String normalizedPinned = normalizeFingerprint(pinnedFingerprint);

        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                for (X509TrustManager trustManager : defaults) {
                    trustManager.checkClientTrusted(chain, authType);
                    return;
                }
                throw new CertificateException("No default trust manager available");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                CertificateException lastException = null;
                for (X509TrustManager trustManager : defaults) {
                    try {
                        trustManager.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException exception) {
                        lastException = exception;
                    }
                }

                if (!normalizedPinned.isEmpty() && chain != null && chain.length > 0) {
                    String actual = sha256Fingerprint(chain[0]);
                    if (actual.equalsIgnoreCase(normalizedPinned)) {
                        Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] QUIC server certificate accepted by pinned fingerprint.");
                        return;
                    }
                }

                if (lastException != null) {
                    throw lastException;
                }
                throw new CertificateException("Server certificate validation failed");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> issuers = new ArrayList<>();
                for (X509TrustManager trustManager : defaults) {
                    X509Certificate[] accepted = trustManager.getAcceptedIssuers();
                    if (accepted != null) {
                        for (X509Certificate certificate : accepted) {
                            issuers.add(certificate);
                        }
                    }
                }
                return issuers.toArray(new X509Certificate[0]);
            }
        };
    }

    private static List<X509TrustManager> loadDefaultTrustManagers() {
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((java.security.KeyStore) null);
            List<X509TrustManager> result = new ArrayList<>();
            for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager x509) {
                    result.add(x509);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception exception) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Failed to load default trust managers for QUIC.", exception);
        }
        throw new IllegalStateException("Default X509TrustManager is unavailable");
    }

    private static String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        return fingerprint.replace(":", "").replace("-", "").trim().toUpperCase();
    }

    private static String sha256Fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] encoded = certificate.getEncoded();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(String.format("%02X", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new CertificateException("SHA-256 is unavailable", exception);
        }
    }

    private static void closeStreamHierarchy(QuicStreamChannel stream) {
        stream.close().syncUninterruptibly();
        Channel quicChannel = stream.parent();
        if (quicChannel != null) {
            quicChannel.close().syncUninterruptibly();
            Channel udpChannel = quicChannel.parent();
            if (udpChannel != null) {
                udpChannel.close().syncUninterruptibly();
            }
        }
    }
}


