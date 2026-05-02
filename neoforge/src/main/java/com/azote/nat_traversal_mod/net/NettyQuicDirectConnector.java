package com.azote.nat_traversal_mod.net;

import io.netty.bootstrap.Bootstrap;
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
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class NettyQuicDirectConnector implements QuicDirectConnector {
    private static final long MAX_DATA = 2_097_152L;

    @Override
    public boolean isOperational() {
        return true;
    }

    @Override
    public Optional<ChannelFuture> connect(InetSocketAddress address, boolean useEpoll, Connection connection) {
        boolean useNativeEpoll = Epoll.isAvailable() && useEpoll;
        EventLoopGroup ioGroup = useNativeEpoll ? Connection.NETWORK_EPOLL_WORKER_GROUP.get() : Connection.NETWORK_WORKER_GROUP.get();

        var codec = new QuicClientCodecBuilder()
                .sslContext(QuicSslContextBuilder.forClient().applicationProtocols("minecraft").build())
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

        QuicChannel quicChannel = QuicChannel.newBootstrap(udpChannel)
                .streamHandler(new ChannelInboundHandlerAdapter())
                .remoteAddress(address)
                .connect()
                .getNow();

        if (quicChannel == null) {
            udpChannel.close().syncUninterruptibly();
            return Optional.empty();
        }

        QuicStreamChannel stream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel channel) {
                Connection.configureSerialization(channel.pipeline(), PacketFlow.CLIENTBOUND, false, null);
                connection.configurePacketHandler(channel.pipeline());
            }
        }).getNow();

        if (stream == null) {
            quicChannel.close().syncUninterruptibly();
            udpChannel.close().syncUninterruptibly();
            return Optional.empty();
        }

        return Optional.of(stream.newSucceededFuture());
    }
}

