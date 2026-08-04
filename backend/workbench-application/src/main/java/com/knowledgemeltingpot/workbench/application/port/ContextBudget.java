package com.knowledgemeltingpot.workbench.application.port;

/**
 * Server-side caps for trusted context assembly. The caller may never exceed
 * these limits; both values are validated on construction.
 */
public record ContextBudget(int topK, int maxTotalChars) {

    public static final int MAX_TOP_K = 100;
    public static final int MAX_TOTAL_CHARS = 1_000_000;

    public ContextBudget {
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and " + MAX_TOP_K);
        }
        if (maxTotalChars < 1 || maxTotalChars > MAX_TOTAL_CHARS) {
            throw new IllegalArgumentException("maxTotalChars must be between 1 and " + MAX_TOTAL_CHARS);
        }
    }

    public static ContextBudget defaults() {
        return new ContextBudget(50, 200_000);
    }
}
