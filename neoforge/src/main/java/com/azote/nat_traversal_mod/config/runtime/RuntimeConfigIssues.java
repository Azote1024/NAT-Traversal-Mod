package com.azote.nat_traversal_mod.config.runtime;

import java.util.List;

public record RuntimeConfigIssues(List<String> warnings) {
    public static RuntimeConfigIssues empty() {
        return new RuntimeConfigIssues(List.of());
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}

