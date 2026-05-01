package com.azote.nat_traversal_mod;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<String> SUPABASE_URL_VALUE = BUILDER
            .comment("Supabase project URL. Example: https://xxxx.supabase.co")
            .define("supabase_url", """");

    private static final ModConfigSpec.ConfigValue<String> SUPABASE_KEY_VALUE = BUILDER
            .comment("Supabase publishable key used for REST requests")
            .define("supabase_key", """");

    private static final ModConfigSpec.ConfigValue<String> ROOM_NAME_VALUE = BUILDER
            .comment("Fixed room_name key in public.rooms")
            .define("room_name", "default_room");

    private static final ModConfigSpec.ConfigValue<String> INTERCEPT_HOST_VALUE = BUILDER
            .comment("Only this exact host is intercepted")
            .define("intercept_host", "play.mc.local");

    private static final ModConfigSpec.ConfigValue<String> PUBLISH_HOST_NAME_VALUE = BUILDER
            .comment("Server-side publish host_name")
            .define("publish_host_name", "host");

    private static final ModConfigSpec.ConfigValue<String> PUBLISH_HOST_IP_VALUE = BUILDER
            .comment("Server-side publish host_ip (public IP or DDNS target)")
            .define("publish_host_ip", "");

    private static final ModConfigSpec.BooleanValue STUN_ENABLED_VALUE = BUILDER
            .comment("Enable STUN flow (reserved for future implementation)")
            .define("stun_enabled", false);

    private static final ModConfigSpec.ConfigValue<String> STUN_SERVER_VALUE = BUILDER
            .comment("Primary STUN server host:port (reserved for future implementation)")
            .define("stun_server", "stun.l.google.com:19302");

    private static final ModConfigSpec.IntValue STUN_TIMEOUT_MS_VALUE = BUILDER
            .comment("STUN timeout in milliseconds (reserved for future implementation)")
            .defineInRange("stun_timeout_ms", 3000, 500, 10000);

    private static final ModConfigSpec.ConfigValue<String> RELAY_ENDPOINT_VALUE = BUILDER
            .comment("Relay endpoint host:port (for self-hosted relay server)")
            .define("relay_endpoint", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_PUBLISH_ENDPOINT_VALUE = BUILDER
            .comment("Relay endpoint written to rooms (publicly reachable host:port)")
            .define("relay_publish_endpoint", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_CONNECT_ENDPOINT_VALUE = BUILDER
            .comment("Relay endpoint used by local connector (host-side reachable host:port)")
            .define("relay_connect_endpoint", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_TOKEN_VALUE = BUILDER
            .comment("Relay token used by host/client relay connectors")
            .define("relay_token", "");

    private static final ModConfigSpec.ConfigValue<String> RELAY_STATUS_VALUE = BUILDER
            .comment("Relay status: ready or down")
            .define("relay_status", "down");

    private static final ModConfigSpec.BooleanValue RELAY_CLIENT_CONNECTOR_ENABLED_VALUE = BUILDER
            .comment("Enable local relay client connector path")
            .define("relay_client_connector_enabled", false);

    private static final ModConfigSpec.IntValue RELAY_CLIENT_LOCAL_PORT_VALUE = BUILDER
            .comment("Local relay client connector port")
            .defineInRange("relay_client_local_port", 26667, 1, 65535);

    static final ModConfigSpec SPEC = BUILDER.build();

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

    public static String relayEndpoint() {
        return RELAY_ENDPOINT_VALUE.get().trim();
    }

    public static String relayPublishEndpoint() {
        String value = RELAY_PUBLISH_ENDPOINT_VALUE.get().trim();
        return value.isBlank() ? relayEndpoint() : value;
    }

    public static String relayConnectEndpoint() {
        String value = RELAY_CONNECT_ENDPOINT_VALUE.get().trim();
        return value.isBlank() ? relayEndpoint() : value;
    }

    public static String relayToken() {
        return RELAY_TOKEN_VALUE.get().trim();
    }

    public static String relayStatus() {
        return RELAY_STATUS_VALUE.get().trim();
    }

    public static boolean relayClientConnectorEnabled() {
        return RELAY_CLIENT_CONNECTOR_ENABLED_VALUE.get();
    }

    public static int relayClientLocalPort() {
        return RELAY_CLIENT_LOCAL_PORT_VALUE.get();
    }
}

