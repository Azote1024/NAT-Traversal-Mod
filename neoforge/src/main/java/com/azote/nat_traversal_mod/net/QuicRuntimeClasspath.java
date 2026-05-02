package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Nat_traversal_mod;

final class QuicRuntimeClasspath {
    private static final String QUIC_TOKEN_HANDLER_CLASS = "io.netty.incubator.codec.quic.QuicTokenHandler";

    private QuicRuntimeClasspath() {
    }

    static boolean ensureAvailable() {
        if (isQuicClassVisible()) {
            return true;
        }
        Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] QUIC runtime classes are not visible in current classloaders.");
        return false;
    }

    private static boolean isQuicClassVisible() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (isVisibleIn(contextLoader)) {
            return true;
        }
        ClassLoader selfLoader = QuicRuntimeClasspath.class.getClassLoader();
        return isVisibleIn(selfLoader);
    }

    private static boolean isVisibleIn(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName(QUIC_TOKEN_HANDLER_CLASS, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

