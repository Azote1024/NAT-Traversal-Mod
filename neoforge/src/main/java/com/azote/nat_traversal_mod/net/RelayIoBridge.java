package com.azote.nat_traversal_mod.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

final class RelayIoBridge {
    private RelayIoBridge() {
    }

    static void bridge(Socket a, Socket b) throws InterruptedException {
        Thread aToB = new Thread(() -> copy(a, b), "nat-relay-a-to-b");
        Thread bToA = new Thread(() -> copy(b, a), "nat-relay-b-to-a");
        aToB.setDaemon(true);
        bToA.setDaemon(true);
        aToB.start();
        bToA.start();
        aToB.join();
        bToA.join();
    }

    private static void copy(Socket src, Socket dst) {
        try (InputStream input = src.getInputStream(); OutputStream output = dst.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
            // Connection closure is expected during relay shutdown.
        } finally {
            closeQuietly(src);
            closeQuietly(dst);
        }
    }

    static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}

