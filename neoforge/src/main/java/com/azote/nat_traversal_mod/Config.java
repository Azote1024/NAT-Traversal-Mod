package com.azote.nat_traversal_mod;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final int TCP_ATTEMPTS_DEFAULT = 2;
    private static final int QUIC_ATTEMPTS_DEFAULT = 3;
    private static final int QUIC_ATTEMPT_INTERVAL_MS_DEFAULT = 700;
    private static final int ROUTE_STAGE_RESET_MS_DEFAULT = 30_000;
    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<String> SUPABASE_URL_VALUE = COMMON_BUILDER
            .comment("Supabase project URL. Example: https://xxxx.supabase.co")
            .define("supabase.url", "");

    private static final ModConfigSpec.ConfigValue<String> SUPABASE_KEY_VALUE = COMMON_BUILDER
            .comment("Supabase publishable key used for REST requests")
            .define("supabase.api_key", "");

    private static final ModConfigSpec.ConfigValue<String> ROOM_NAME_VALUE = COMMON_BUILDER
            .comment("Fixed room_name key in public.rooms")
            .define("mode.room_name", "default_room");

    private static final ModConfigSpec.ConfigValue<String> CONNECT_STRATEGY_VALUE = COMMON_BUILDER
            .comment("Connection strategy: tcp_only, quic_first, relay_first, tcp_quic_relay")
            .define("mode.connect_strategy", "tcp_quic_relay");

    private static final ModConfigSpec.ConfigValue<String> INTERCEPT_HOST_VALUE = CLIENT_BUILDER
            .comment("Only this exact host is intercepted")
            .define("mode.intercept_host", "play.mc.local");

    private static final ModConfigSpec.ConfigValue<String> PUBLISH_HOST_NAME_VALUE = SERVER_BUILDER
            .comment("Server-side publish host_name")
            .define("publish.host_name", "host");

    private static final ModConfigSpec.ConfigValue<String> PUBLISH_HOST_IP_VALUE = SERVER_BUILDER
            .comment("Server-side publish host_ip (public IP or DDNS target)")
            .define("publish.host_ip", "");

    private static final ModConfigSpec.BooleanValue STUN_ENABLED_VALUE = COMMON_BUILDER
            .comment("Enable STUN flow")
            .define("stun.enabled", false);

    private static final ModConfigSpec.ConfigValue<String> STUN_SERVER_VALUE = COMMON_BUILDER
            .comment("Primary STUN server host:port")
            .define("stun.server", "stun.l.google.com:19302");

    private static final ModConfigSpec.IntValue STUN_TIMEOUT_MS_VALUE = COMMON_BUILDER
            .comment("STUN timeout in milliseconds")
            .defineInRange("stun.timeout_ms", 3000, 500, 10000);

    private static final ModConfigSpec.ConfigValue<String> RELAY_PUBLISH_ENDPOINT_VALUE = SERVER_BUILDER
            .comment("Relay endpoint written to rooms (publicly reachable host:port)")
            .define("relay.publish_endpoint", "");


    private static final ModConfigSpec.ConfigValue<String> RELAY_CONNECT_ENDPOINT_SERVER_VALUE = SERVER_BUILDER
            .comment("Relay endpoint used by server-side relay connector (host-side reachable host:port)")
            .define("relay.connect_endpoint", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_CONNECT_ENDPOINT_CLIENT_VALUE = CLIENT_BUILDER
            .comment("Relay endpoint used by client-side relay connector (client-side reachable host:port)")
            .define("relay.connect_endpoint", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_TOKEN_VALUE = COMMON_BUILDER
            .comment("Relay token used by host/client relay connectors")
            .define("relay.token", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_STATUS_VALUE = SERVER_BUILDER
            .comment("Relay status: ready or down")
            .define("relay.status", "down");

    private static final ModConfigSpec.ConfigValue<String> QUIC_PUBLISH_ENDPOINT_VALUE = SERVER_BUILDER
            .comment("QUIC endpoint written to rooms (publicly reachable host:port)")
            .define("quic.publish_endpoint", "");

    private static final ModConfigSpec.ConfigValue<String> QUIC_STATUS_VALUE = SERVER_BUILDER
            .comment("QUIC status: ready or down")
            .define("quic.status", "down");

    private static final ModConfigSpec.ConfigValue<String> QUIC_TLS_CERT_FILE_VALUE = SERVER_BUILDER
            .comment("QUIC server TLS certificate file path (PEM)")
            .define("quic.cert_file", "");

    private static final ModConfigSpec.ConfigValue<String> QUIC_TLS_KEY_FILE_VALUE = SERVER_BUILDER
            .comment("QUIC server TLS private key file path (PEM)")
            .define("quic.key_file", "");

    private static final ModConfigSpec.BooleanValue RELAY_CLIENT_CONNECTOR_ENABLED_VALUE = CLIENT_BUILDER
            .comment("Enable local relay client connector path")
            .define("relay.enabled", false);

    private static final ModConfigSpec.IntValue RELAY_CLIENT_LOCAL_PORT_VALUE = CLIENT_BUILDER
            .comment("Local relay client connector port")
            .defineInRange("relay.local_port", 26667, 1, 65535);

    private static final ModConfigSpec.IntValue TCP_ATTEMPTS_VALUE = CLIENT_BUILDER
            .comment("Number of direct TCP attempts before entering QUIC stage in tcp_quic_relay mode")
            .defineInRange("routing.tcp_attempts", TCP_ATTEMPTS_DEFAULT, 0, 10);

    private static final ModConfigSpec.BooleanValue QUIC_ENABLED_VALUE = CLIENT_BUILDER
            .comment("Enable QUIC P2P route")
            .define("quic.enabled", true);

    private static final ModConfigSpec.IntValue QUIC_ATTEMPTS_VALUE = CLIENT_BUILDER
            .comment("Number of QUIC attempts before fallback")
            .defineInRange("quic.attempts", QUIC_ATTEMPTS_DEFAULT, 1, 10);

    private static final ModConfigSpec.IntValue QUIC_ATTEMPT_INTERVAL_MS_VALUE = CLIENT_BUILDER
            .comment("Delay between QUIC attempts in milliseconds")
            .defineInRange("quic.attempt_interval_ms", QUIC_ATTEMPT_INTERVAL_MS_DEFAULT, 100, 5000);

    private static final ModConfigSpec.IntValue ROUTE_STAGE_RESET_MS_VALUE = CLIENT_BUILDER
            .comment("Reset window for tcp_quic_relay stage counters in milliseconds")
            .defineInRange("routing.stage_reset_ms", ROUTE_STAGE_RESET_MS_DEFAULT, 1000, 600000);

    private static final ModConfigSpec.ConfigValue<String> QUIC_TLS_MODE_VALUE = CLIENT_BUILDER
            .comment("QUIC TLS mode: ca_or_pinned or insecure_trust_all")
            .define("quic.tls_mode", "ca_or_pinned");

    private static final ModConfigSpec.ConfigValue<String> QUIC_CERT_FINGERPRINT_SHA256_VALUE = CLIENT_BUILDER
            .comment("Pinned SHA-256 cert fingerprint for self-signed mode")
            .define("quic.cert_fingerprint_sha256", "");

    static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();
    static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
    static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    private Config() {
    }

    public static String modeConnectStrategy() {
        String mode = CONNECT_STRATEGY_VALUE.get().trim().toLowerCase();
        if (mode.equals("tcp_only") || mode.equals("relay_first") || mode.equals("quic_first") || mode.equals("tcp_quic_relay")) {
            return mode;
        }
        return "tcp_quic_relay";
    }

    public static String modeInterceptHost() {
        return getClientStringOrDefault(INTERCEPT_HOST_VALUE, "play.mc.local");
    }

    public static String modeRoomName() {
        return ROOM_NAME_VALUE.get().trim();
    }

    public static String supabaseUrl() {
        return SUPABASE_URL_VALUE.get().trim();
    }

    public static String supabaseApiKey() {
        return SUPABASE_KEY_VALUE.get().trim();
    }

    public static String publishHostName() {
        return PUBLISH_HOST_NAME_VALUE.get().trim();
    }

    public static String publishHostIp() {
        return PUBLISH_HOST_IP_VALUE.get().trim();
    }

    public static String relayPublishEndpoint() {
        return RELAY_PUBLISH_ENDPOINT_VALUE.get().trim();
    }

    public static String relayConnectEndpointClient() {
        return getClientStringOrDefault(RELAY_CONNECT_ENDPOINT_CLIENT_VALUE, "");
    }

    public static String relayConnectEndpointServer() {
        return RELAY_CONNECT_ENDPOINT_SERVER_VALUE.get().trim();
    }

    public static String relayToken() {
        return RELAY_TOKEN_VALUE.get().trim();
    }

    public static String relayStatus() {
        return RELAY_STATUS_VALUE.get().trim();
    }

    public static boolean relayEnabled() {
        return getClientBooleanOrDefault(RELAY_CLIENT_CONNECTOR_ENABLED_VALUE, false);
    }

    public static int relayLocalPort() {
        return getClientIntOrDefault(RELAY_CLIENT_LOCAL_PORT_VALUE, 26667);
    }

    public static boolean stunEnabled() {
        return STUN_ENABLED_VALUE.get();
    }

    public static String stunServer() {
        return STUN_SERVER_VALUE.get().trim();
    }

    public static int stunTimeoutMs() {
        return STUN_TIMEOUT_MS_VALUE.get();
    }

    public static boolean quicEnabled() {
        return getClientBooleanOrDefault(QUIC_ENABLED_VALUE, true);
    }

    public static String quicPublishEndpoint() {
        return QUIC_PUBLISH_ENDPOINT_VALUE.get().trim();
    }

    public static String quicStatus() {
        return QUIC_STATUS_VALUE.get().trim();
    }

    public static String quicTlsModeName() {
        String mode = getClientStringOrDefault(QUIC_TLS_MODE_VALUE, "ca_or_pinned").toLowerCase();
        if (mode.equals("ca_or_pinned") || mode.equals("insecure_trust_all")) {
            return mode;
        }
        return "ca_or_pinned";
    }

    public static String quicTlsCertFile() {
        return QUIC_TLS_CERT_FILE_VALUE.get().trim();
    }

    public static String quicTlsKeyFile() {
        return QUIC_TLS_KEY_FILE_VALUE.get().trim();
    }

    public static String quicCertFingerprintSha256() {
        return getClientStringOrDefault(QUIC_CERT_FINGERPRINT_SHA256_VALUE, "");
    }

    public static int quicAttempts() {
        return getClientIntOrDefault(QUIC_ATTEMPTS_VALUE, QUIC_ATTEMPTS_DEFAULT);
    }

    public static int routingTcpAttempts() {
        return getClientIntOrDefault(TCP_ATTEMPTS_VALUE, TCP_ATTEMPTS_DEFAULT);
    }

    public static int quicAttemptIntervalMs() {
        return getClientIntOrDefault(QUIC_ATTEMPT_INTERVAL_MS_VALUE, QUIC_ATTEMPT_INTERVAL_MS_DEFAULT);
    }

    public static int routingStageResetMs() {
        return getClientIntOrDefault(ROUTE_STAGE_RESET_MS_VALUE, ROUTE_STAGE_RESET_MS_DEFAULT);
    }


    private static int getClientIntOrDefault(ModConfigSpec.IntValue value, int defaultValue) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            // Dedicated server startup can access client-only config values before client config is loaded.
            return defaultValue;
        }
    }

    private static boolean getClientBooleanOrDefault(ModConfigSpec.BooleanValue value, boolean defaultValue) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            // Dedicated server startup can access client-only config values before client config is loaded.
            return defaultValue;
        }
    }

    private static String getClientStringOrDefault(ModConfigSpec.ConfigValue<String> value, String defaultValue) {
        try {
            return value.get().trim();
        } catch (IllegalStateException ignored) {
            // Dedicated server startup can access client-only config values before client config is loaded.
            return defaultValue;
        }
    }
}


