package com.knowledgemeltingpot.workbench.application.error;

/** Stable, sanitized Provider failure shared by ingestion and synchronous retrieval. */
public final class EmbeddingProviderException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public EmbeddingProviderException(String code, boolean retryable) {
        super(code);
        if (code == null || !code.matches("[A-Z0-9_]{3,100}")) {
            throw new IllegalArgumentException("embedding Provider code is invalid");
        }
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
