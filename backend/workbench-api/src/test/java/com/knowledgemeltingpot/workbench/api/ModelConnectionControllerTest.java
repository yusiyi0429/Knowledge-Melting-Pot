package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ModelConnectionControllerTest {

    @Test
    void responseSerializesOnlyCredentialConfiguredFlag() throws Exception {
        String sealedCredential = "kmp1.secret-envelope";
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        ModelConnection connection = new ModelConnection(UUID.randomUUID(), "企业网关",
                ModelProvider.OPENAI_COMPATIBLE, URI.create("https://api.example.com/v1"),
                Optional.of(new CredentialEnvelope(sealedCredential)), true,
                ModelConnectionValidationStatus.UNTESTED, null, UUID.randomUUID(), now, now);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(
                ModelConnectionController.ModelConnectionResponse.from(connection));

        assertThat(json).contains("\"credentialConfigured\":true")
                .doesNotContain("credentialEnvelope", "encoded", sealedCredential);
    }

    @Test
    void credentialBearingRequestsHaveRedactedToString() {
        String credential = "credential-do-not-log";
        var request = new ModelConnectionController.ModelConnectionRequest("企业网关",
                ModelProvider.OPENAI_COMPATIBLE, "https://api.example.com/v1", credential, true);

        assertThat(request.toString()).contains("credential=REDACTED").doesNotContain(credential);
    }

    @Test
    void requestCredentialIsWriteOnlyAtRuntimeAsWellAsInOpenApi() throws Exception {
        String credential = "credential-do-not-serialize";
        var request = new ModelConnectionController.ModelConnectionRequest("企业网关",
                ModelProvider.OPENAI_COMPATIBLE, "https://api.example.com/v1", credential, true);

        String json = new ObjectMapper().writeValueAsString(request);

        assertThat(json).doesNotContain("credential", credential);
    }

    @Test
    void everyEndpointIsRestrictedToAdministratorsAtTheControllerBoundary() {
        PreAuthorize authorization = ModelConnectionController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }
}
