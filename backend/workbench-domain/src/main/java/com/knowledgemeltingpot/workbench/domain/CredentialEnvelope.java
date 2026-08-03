package com.knowledgemeltingpot.workbench.domain;

import java.util.Objects;

/**
 * Opaque encrypted credential storage value. Its contents must never be returned by an API or logged.
 */
public final class CredentialEnvelope {
    private final String encoded;

    public CredentialEnvelope(String encoded) {
        this.encoded = DomainChecks.text(encoded, "encoded");
    }

    public String encoded() {
        return encoded;
    }

    @Override
    public String toString() {
        return "CredentialEnvelope[REDACTED]";
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CredentialEnvelope that && encoded.equals(that.encoded);
    }

    @Override
    public int hashCode() {
        return Objects.hash(encoded);
    }
}
