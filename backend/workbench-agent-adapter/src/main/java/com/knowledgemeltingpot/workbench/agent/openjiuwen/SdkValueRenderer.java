package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

/** Extracts a stable text value from SDK-owned dynamic payloads. */
final class SdkValueRenderer {
    private static final String[] PREFERRED_KEYS = {
            "content", "output", "response", "answer", "result", "payload", "message"
    };

    private SdkValueRenderer() {
    }

    static String render(Object value) {
        return render(value, 0);
    }

    private static String render(Object value, int depth) {
        if (value == null) {
            return "";
        }
        if (depth > 8) {
            return "";
        }
        if (value instanceof CharSequence sequence) {
            return sequence.toString();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : PREFERRED_KEYS) {
                if (map.containsKey(key)) {
                    String rendered = render(map.get(key), depth + 1);
                    if (!rendered.isBlank()) {
                        return rendered;
                    }
                }
            }
            StringBuilder builder = new StringBuilder();
            for (Object nested : map.values()) {
                append(builder, render(nested, depth + 1));
            }
            return builder.toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder builder = new StringBuilder();
            for (Object nested : collection) {
                append(builder, render(nested, depth + 1));
            }
            return builder.toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < Array.getLength(value); index++) {
                append(builder, render(Array.get(value, index), depth + 1));
            }
            return builder.toString();
        }
        return String.valueOf(value);
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(value);
    }
}
