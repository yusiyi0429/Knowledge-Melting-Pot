package com.knowledgemeltingpot.workbench.domain;

import java.util.Objects;

final class DomainChecks {
    private DomainChecks() {
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " is required");
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
