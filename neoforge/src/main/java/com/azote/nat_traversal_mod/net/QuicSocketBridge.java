package com.azote.nat_traversal_mod.net;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
final class QuicSocketBridge {
    private QuicSocketBridge() {
    }
    static void bridge(Socket socket, QuicStreamChannel stream, String threadName) throws IOException {
        OutputStream socketOutput = socket.getOutputStream();
        stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof ByteBuf byteBuf) {
                    byte[] data = new byte[byteBuf.readableBytes()];
                    byteBuf.readBytes(data);
                    socketOutput.write(data);
                    socketOutput.flush();
                    byteBuf.release();
                    return;
                }
                super.channelRead(ctx, msg);
            }
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                RelayIoBridge.closeQuietly(socket);
                super.channelInactive(ctx);
            }
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                ctx.close();
            }
        });
        // Resume reading only after inbound bridge handler is attached.
        stream.config().setAutoRead(true);
        Thread writer = new Thread(() -> {
            try (InputStream socketInput = socket.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = socketInput.read(buffer)) != -1) {
                    stream.writeAndFlush(Unpooled.wrappedBuffer(buffer, 0, read)).syncUninterruptibly();
                }
            } catch (IOException ignored) {
            } finally {
                stream.close().syncUninterruptibly();
                RelayIoBridge.closeQuietly(socket);
            }
        }, threadName);
        writer.setDaemon(true);
        writer.start();
    }
}
