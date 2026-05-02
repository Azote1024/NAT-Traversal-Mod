package com.azote.nat_traversal_mod.net;

final class QuicErrorCodes {
    static final String START_FAILED = "start_failed";
    static final String BIND_FAILED = "bind_failed";
    static final String NATIVE_UNAVAILABLE = "native_unavailable";
    static final String TLS_FAILED = "tls_failed";
    static final String CONNECT_FAILED = "connect_failed";
    static final String STREAM_FAILED = "stream_failed";

    private QuicErrorCodes() {
    }

    static String classifyServerStartError(Throwable throwable, boolean bindFailure) {
        if (throwable == null) {
            return START_FAILED;
        }
        if (bindFailure) {
            return BIND_FAILED;
        }
        if (throwable instanceof UnsatisfiedLinkError) {
            return NATIVE_UNAVAILABLE;
        }

        String text = throwable.toString().toLowerCase();
        if (containsAny(text, "native", "quiche")) {
            return NATIVE_UNAVAILABLE;
        }
        if (containsAny(text, "cert", "tls", "ssl")) {
            return TLS_FAILED;
        }
        return START_FAILED;
    }

    static String classifyDirectConnectError(Throwable throwable) {
        if (throwable == null) {
            return CONNECT_FAILED;
        }

        String text = throwable.toString().toLowerCase();
        if (containsAny(text, "native", "quiche")) {
            return NATIVE_UNAVAILABLE;
        }
        if (containsAny(text, "cert", "tls", "ssl")) {
            return TLS_FAILED;
        }
        return CONNECT_FAILED;
    }

    static String classifyDirectStreamError(Throwable throwable) {
        if (throwable == null) {
            return STREAM_FAILED;
        }

        String text = throwable.toString().toLowerCase();
        if (containsAny(text, "cert", "tls", "ssl")) {
            return TLS_FAILED;
        }
        return STREAM_FAILED;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

