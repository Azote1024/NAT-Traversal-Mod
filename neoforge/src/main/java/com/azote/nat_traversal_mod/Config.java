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
}
