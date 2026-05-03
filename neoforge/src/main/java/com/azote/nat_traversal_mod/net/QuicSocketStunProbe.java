package com.azote.nat_traversal_mod.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class QuicSocketStunProbe {
    private static final SecureRandom RANDOM = new SecureRandom();

    private QuicSocketStunProbe() {
    }

    static Optional<String> resolvePublicEndpoint(Channel udpChannel, String stunServer, int timeoutMs) {
        if (udpChannel == null || stunServer == null || stunServer.isBlank()) {
            return Optional.empty();
        }

        Optional<InetSocketAddress> serverAddress = StunClient.resolveServerAddress(stunServer);
        if (serverAddress.isEmpty()) {
            return Optional.empty();
        }

        byte[] txId = new byte[12];
        RANDOM.nextBytes(txId);
        byte[] request = StunClient.createBindingRequest(txId);
        AtomicReference<String> resolved = new AtomicReference<>(null);

        String handlerName = "nat-stun-probe-" + System.nanoTime();
        var handler = new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                ByteBuf content = msg.content();
                byte[] bytes = new byte[content.readableBytes()];
                content.getBytes(content.readerIndex(), bytes);
                Optional<String> parsed = StunClient.parseBindingResponseForTxId(bytes, bytes.length, txId);
                if (parsed.isPresent()) {
                    resolved.set(parsed.get());
                }
                ctx.fireChannelRead(msg.retain());
            }
        };

        udpChannel.pipeline().addFirst(handlerName, handler);
        try {
            DatagramPacket packet = new DatagramPacket(Unpooled.wrappedBuffer(request), serverAddress.get());
            udpChannel.writeAndFlush(packet).syncUninterruptibly();

            long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);
            while (System.currentTimeMillis() < deadline && resolved.get() == null) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return Optional.ofNullable(resolved.get());
        } finally {
            if (udpChannel.pipeline().get(handlerName) != null) {
                udpChannel.pipeline().remove(handlerName);
            }
        }
    }
}

