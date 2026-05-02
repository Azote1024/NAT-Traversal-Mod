package com.azote.nat_traversal_mod.net.supabase;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JsonRegexDtoParser {
    record JsonFieldMatch(String rawValue) {
        String trimmedValue() {
            return rawValue == null ? "" : rawValue.trim();
        }
    }

    private JsonRegexDtoParser() {
    }

    static Optional<JsonFieldMatch> findFirst(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new JsonFieldMatch(matcher.group(1)));
    }
}

