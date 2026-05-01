package com.azote.nat_traversal_mod;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<String> SUPABASE_URL_VALUE = COMMON_BUILDER
            .comment("Supabase project URL. Example: https://xxxx.supabase.co")
            .define("supabase_url", "");

    private static final ModConfigSpec.ConfigValue<String> SUPABASE_KEY_VALUE = COMMON_BUILDER
            .comment("Supabase publishable key used for REST requests")
            .define("supabase_key", "");

    private static final ModConfigSpec.ConfigValue<String> ROOM_NAME_VALUE = COMMON_BUILDER
            .comment("Fixed room_name key in public.rooms")
            .define("room_name", "default_room");

    private static final ModConfigSpec.ConfigValue<String> INTERCEPT_HOST_VALUE = CLIENT_BUILDER
            .comment("Only this exact host is intercepted")
            .define("intercept_host", "play.mc.local");

    private static final ModConfigSpec.ConfigValue<String> PUBLISH_HOST_NAME_VALUE = SERVER_BUILDER
            .comment("Server-side publish host_name")
            .define("publish_host_name", "host");

    private static final ModConfigSpec.ConfigValue<String> PUBLISH_HOST_IP_VALUE = SERVER_BUILDER
            .comment("Server-side publish host_ip (public IP or DDNS target)")
            .define("publish_host_ip", "");

    private static final ModConfigSpec.BooleanValue STUN_ENABLED_VALUE = COMMON_BUILDER
            .comment("Enable STUN flow")
            .define("stun_enabled", false);

    private static final ModConfigSpec.ConfigValue<String> STUN_SERVER_VALUE = COMMON_BUILDER
            .comment("Primary STUN server host:port")
            .define("stun_server", "stun.l.google.com:19302");

    private static final ModConfigSpec.IntValue STUN_TIMEOUT_MS_VALUE = COMMON_BUILDER
            .comment("STUN timeout in milliseconds")
            .defineInRange("stun_timeout_ms", 3000, 500, 10000);

    private static final ModConfigSpec.ConfigValue<String> RELAY_PUBLISH_ENDPOINT_VALUE = SERVER_BUILDER
            .comment("Relay endpoint written to rooms (publicly reachable host:port)")
            .define("relay_publish_endpoint", "");


    private static final ModConfigSpec.ConfigValue<String> RELAY_CONNECT_ENDPOINT_SERVER_VALUE = SERVER_BUILDER
            .comment("Relay endpoint used by server-side relay connector (host-side reachable host:port)")
            .define("relay_connect_endpoint_server", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_CONNECT_ENDPOINT_CLIENT_VALUE = CLIENT_BUILDER
            .comment("Relay endpoint used by client-side relay connector (client-side reachable host:port)")
            .define("relay_connect_endpoint_client", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_TOKEN_VALUE = COMMON_BUILDER
            .comment("Relay token used by host/client relay connectors")
            .define("relay_token", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_STATUS_VALUE = SERVER_BUILDER
            .comment("Relay status: ready or down")
            .define("relay_status", "down");

    private static final ModConfigSpec.ConfigValue<String> QUIC_PUBLISH_ENDPOINT_VALUE = SERVER_BUILDER
            .comment("QUIC endpoint written to rooms (publicly reachable host:port)")
            .define("quic_publish_endpoint", "");

    private static final ModConfigSpec.ConfigValue<String> QUIC_STATUS_VALUE = SERVER_BUILDER
            .comment("QUIC status: ready or down")
            .define("quic_status", "down");

    private static final ModConfigSpec.BooleanValue RELAY_CLIENT_CONNECTOR_ENABLED_VALUE = CLIENT_BUILDER
            .comment("Enable local relay client connector path")
            .define("relay_client_connector_enabled", false);

    private static final ModConfigSpec.IntValue RELAY_CLIENT_LOCAL_PORT_VALUE = CLIENT_BUILDER
            .comment("Local relay client connector port")
            .defineInRange("relay_client_local_port", 26667, 1, 65535);

    private static final ModConfigSpec.ConfigValue<String> RELAY_PRIORITY_MODE_VALUE = CLIENT_BUILDER
            .comment("Route priority mode: public_first, relay_first, or quic_first")
            .define("relay_priority_mode", "public_first");

    private static final ModConfigSpec.BooleanValue QUIC_ENABLED_VALUE = CLIENT_BUILDER
            .comment("Enable QUIC P2P route")
            .define("quic_enabled", true);

    private static final ModConfigSpec.IntValue QUIC_ATTEMPTS_VALUE = CLIENT_BUILDER
            .comment("Number of QUIC attempts before fallback")
            .defineInRange("quic_attempts", 3, 1, 10);

    private static final ModConfigSpec.IntValue QUIC_ATTEMPT_INTERVAL_MS_VALUE = CLIENT_BUILDER
            .comment("Delay between QUIC attempts in milliseconds")
            .defineInRange("quic_attempt_interval_ms", 700, 100, 5000);

    private static final ModConfigSpec.ConfigValue<String> QUIC_TLS_MODE_VALUE = CLIENT_BUILDER
            .comment("QUIC TLS mode: ca_or_pinned or insecure_trust_all")
            .define("quic_tls_mode", "ca_or_pinned");

    private static final ModConfigSpec.ConfigValue<String> QUIC_CERT_FINGERPRINT_SHA256_VALUE = CLIENT_BUILDER
            .comment("Pinned SHA-256 cert fingerprint for self-signed mode")
            .define("quic_cert_fingerprint_sha256", "");

    static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();
    static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
    static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    private Config() {
    }

    public static String supabaseUrl() {
        return SUPABASE_URL_VALUE.get().trim();
    }

    public static String supabaseKey() {
        return SUPABASE_KEY_VALUE.get().trim();
    }

    public static String roomName() {
        return ROOM_NAME_VALUE.get().trim();
    }

    public static String interceptHost() {
        return INTERCEPT_HOST_VALUE.get().trim();
    }

    public static String publishHostName() {
        return PUBLISH_HOST_NAME_VALUE.get().trim();
    }

    public static String publishHostIp() {
        return PUBLISH_HOST_IP_VALUE.get().trim();
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

    public static String relayPublishEndpoint() {
        return RELAY_PUBLISH_ENDPOINT_VALUE.get().trim();
    }

    public static String relayConnectEndpointForServer() {
        return RELAY_CONNECT_ENDPOINT_SERVER_VALUE.get().trim();
    }

    public static String relayConnectEndpointForClient() {
        return RELAY_CONNECT_ENDPOINT_CLIENT_VALUE.get().trim();
    }

    public static String relayToken() {
        return RELAY_TOKEN_VALUE.get().trim();
    }

    public static String relayStatus() {
        return RELAY_STATUS_VALUE.get().trim();
    }

    public static String quicPublishEndpoint() {
        return QUIC_PUBLISH_ENDPOINT_VALUE.get().trim();
    }

    public static String quicStatus() {
        return QUIC_STATUS_VALUE.get().trim();
    }

    public static boolean relayClientConnectorEnabled() {
        return RELAY_CLIENT_CONNECTOR_ENABLED_VALUE.get();
    }

    public static int relayClientLocalPort() {
        return RELAY_CLIENT_LOCAL_PORT_VALUE.get();
    }

    public static String relayPriorityMode() {
        String mode = RELAY_PRIORITY_MODE_VALUE.get().trim().toLowerCase();
        if (mode.equals("relay_first") || mode.equals("public_first") || mode.equals("quic_first")) {
            return mode;
        }
        return "public_first";
    }

    public static boolean relayFirstMode() {
        return "relay_first".equals(relayPriorityMode());
    }

    public static boolean quicFirstMode() {
        return "quic_first".equals(relayPriorityMode());
    }

    public static boolean quicEnabled() {
        return QUIC_ENABLED_VALUE.get();
    }

    public static int quicAttempts() {
        return QUIC_ATTEMPTS_VALUE.get();
    }

    public static int quicAttemptIntervalMs() {
        return QUIC_ATTEMPT_INTERVAL_MS_VALUE.get();
    }

    public static String quicTlsMode() {
        String mode = QUIC_TLS_MODE_VALUE.get().trim().toLowerCase();
        if (mode.equals("ca_or_pinned") || mode.equals("insecure_trust_all")) {
            return mode;
        }
        return "ca_or_pinned";
    }

    public static String quicCertFingerprintSha256() {
        return QUIC_CERT_FINGERPRINT_SHA256_VALUE.get().trim();
    }
}

