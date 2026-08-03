package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import java.util.regex.Pattern;

/** Best-effort redaction for exception and diagnostic text. */
final class SensitiveTextRedactor {
    private static final String MASK = "[REDACTED]";
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|access[-_ ]?token|secret|password)"
                    + "(\\s*[=:]\\s*[\\\"]?)([^\\s,;\\\"}]+)");
    private static final Pattern OPENAI_STYLE = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");

    private SensitiveTextRedactor() {
    }

    static String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String redacted = BEARER.matcher(text).replaceAll("$1" + MASK);
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1$2" + MASK);
        return OPENAI_STYLE.matcher(redacted).replaceAll(MASK);
    }
}
