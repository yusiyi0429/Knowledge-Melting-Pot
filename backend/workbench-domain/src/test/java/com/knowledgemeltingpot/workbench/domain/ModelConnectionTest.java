package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelConnectionTest {

    @Test
    void toStringNeverContainsTheCredentialEnvelope() {
        String sealedSecret = "kmp1.this-must-never-appear";
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        ModelConnection connection = new ModelConnection(UUID.randomUUID(), "生产网关",
                ModelProvider.OPENAI_COMPATIBLE, URI.create("https://model.example.com/v1"),
                Optional.of(new CredentialEnvelope(sealedSecret)), true,
                ModelConnectionValidationStatus.UNTESTED, null, UUID.randomUUID(), now, now);

        assertThat(connection.toString()).contains("credentialConfigured=true").doesNotContain(sealedSecret);
        assertThat(connection.credentialEnvelope().orElseThrow().toString()).doesNotContain(sealedSecret);
    }

    @Test
    void validatedConnectionRequiresTimestamp() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");

        assertThatThrownBy(() -> new ModelConnection(UUID.randomUUID(), "生产网关",
                ModelProvider.OPENAI_COMPATIBLE, URI.create("https://model.example.com/v1"), Optional.empty(),
                true, ModelConnectionValidationStatus.CONFIGURATION_VALIDATED, null, UUID.randomUUID(), now, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastValidatedAt");
    }
}
