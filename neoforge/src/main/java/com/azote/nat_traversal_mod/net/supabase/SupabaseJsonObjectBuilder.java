package com.azote.nat_traversal_mod.net.supabase;

final class SupabaseJsonObjectBuilder {
    private final StringBuilder builder = new StringBuilder("{");
    private boolean first = true;

    SupabaseJsonObjectBuilder addString(String key, String value) {
        beginField(key);
        builder.append('"').append(SupabaseJsonUtil.escape(value)).append('"');
        return this;
    }

    SupabaseJsonObjectBuilder addNumber(String key, long value) {
        beginField(key);
        builder.append(value);
        return this;
    }

    SupabaseJsonObjectBuilder addRawJson(String key, String rawJson) {
        beginField(key);
        builder.append(rawJson);
        return this;
    }

    String build() {
        return builder.append('}').toString();
    }

    private void beginField(String key) {
        if (!first) {
            builder.append(',');
        }
        first = false;
        builder.append('"').append(SupabaseJsonUtil.escape(key)).append('"').append(':');
    }
}

