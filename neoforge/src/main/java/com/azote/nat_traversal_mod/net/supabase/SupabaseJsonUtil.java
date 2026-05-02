package com.azote.nat_traversal_mod.net;

final class SupabaseJsonUtil {
    private SupabaseJsonUtil() {
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}


