package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;

final class QuicRuntimeClasspath {
    private static final String QUIC_TOKEN_HANDLER_CLASS = "io.netty.incubator.codec.quic.QuicTokenHandler";
    private static final String QUIC_NATIVE_WINDOWS_RESOURCE = "META-INF/native/netty_quiche_windows_x86_64.dll";

    private QuicRuntimeClasspath() {
    }

    static boolean ensureAvailable() {
        if (isQuicClassVisible()) {
            if (!isResourceVisibleInAnyLoader(QUIC_NATIVE_WINDOWS_RESOURCE)) {
                NatTraversalMod.LOGGER.info("[nat-traversal-mod] QUIC native resource is not visible: {}", QUIC_NATIVE_WINDOWS_RESOURCE);
            }
            return true;
        }
        NatTraversalMod.LOGGER.info("[nat-traversal-mod] QUIC runtime classes are not visible in current classloaders.");
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

    private static boolean isResourceVisibleInAnyLoader(String resourcePath) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (isResourceVisibleIn(contextLoader, resourcePath)) {
            return true;
        }
        ClassLoader selfLoader = QuicRuntimeClasspath.class.getClassLoader();
        return isResourceVisibleIn(selfLoader, resourcePath);
    }

    private static boolean isResourceVisibleIn(ClassLoader loader, String resourcePath) {
        if (loader == null) {
            return false;
        }
        try {
            return loader.getResource(resourcePath) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}


