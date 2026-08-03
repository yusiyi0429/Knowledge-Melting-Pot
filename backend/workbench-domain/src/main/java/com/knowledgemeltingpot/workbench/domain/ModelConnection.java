package com.knowledgemeltingpot.workbench.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class ModelConnection {
    private final UUID id;
    private final String name;
    private final ModelProvider provider;
    private final URI baseUrl;
    private final Optional<CredentialEnvelope> credentialEnvelope;
    private final boolean enabled;
    private final ModelConnectionValidationStatus validationStatus;
    private final Instant lastValidatedAt;
    private final UUID createdBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ModelConnection(UUID id, String name, ModelProvider provider, URI baseUrl,
            Optional<CredentialEnvelope> credentialEnvelope, boolean enabled,
            ModelConnectionValidationStatus validationStatus, Instant lastValidatedAt,
            UUID createdBy, Instant createdAt, Instant updatedAt) {
        this.id = DomainChecks.required(id, "id");
        this.name = DomainChecks.text(name, "name");
        this.provider = DomainChecks.required(provider, "provider");
        this.baseUrl = DomainChecks.required(baseUrl, "baseUrl");
        this.credentialEnvelope = credentialEnvelope == null ? Optional.empty() : credentialEnvelope;
        this.validationStatus = DomainChecks.required(validationStatus, "validationStatus");
        this.createdBy = DomainChecks.required(createdBy, "createdBy");
        this.createdAt = DomainChecks.required(createdAt, "createdAt");
        this.updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        this.enabled = enabled;
        this.lastValidatedAt = lastValidatedAt;
        if (validationStatus == ModelConnectionValidationStatus.CONFIGURATION_VALIDATED && lastValidatedAt == null) {
            throw new IllegalArgumentException("lastValidatedAt is required for a validated connection");
        }
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public ModelProvider provider() {
        return provider;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public Optional<CredentialEnvelope> credentialEnvelope() {
        return credentialEnvelope;
    }

    public boolean enabled() {
        return enabled;
    }

    public ModelConnectionValidationStatus validationStatus() {
        return validationStatus;
    }

    public Instant lastValidatedAt() {
        return lastValidatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean credentialConfigured() {
        return credentialEnvelope.isPresent();
    }

    @Override
    public String toString() {
        return "ModelConnection[id=" + id + ", name=" + name + ", provider=" + provider
                + ", baseUrlHost=" + baseUrl.getHost() + ", credentialConfigured=" + credentialConfigured()
                + ", enabled=" + enabled + ", validationStatus=" + validationStatus
                + ", lastValidatedAt=" + lastValidatedAt + ", createdBy=" + createdBy
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "]";
    }
}
