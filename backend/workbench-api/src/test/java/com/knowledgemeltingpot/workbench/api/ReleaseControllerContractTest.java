package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.Release;
import com.knowledgemeltingpot.workbench.domain.ReleaseCoverage;
import com.knowledgemeltingpot.workbench.domain.ReleaseStatus;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReleaseControllerContractTest {

    @Test
    void releaseResponseDoesNotExposeManifestBodyOrActor() throws Exception {
        String manifest = "{\"secretInternalObjectKey\":true}";
        Release release = new Release(UUID.randomUUID(), UUID.randomUUID(), "v1.2.0", ReleaseStatus.PUBLISHED,
                ReleaseCoverage.PARTIAL, "灰度发布", UUID.randomUUID(), manifest, "abc123", UUID.randomUUID(),
                Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-03T00:00:00Z"));

        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(ReleaseController.ReleaseResponse.from(release));

        assertThat(json)
                .contains("\"tag\":\"v1.2.0\"")
                .contains("\"coverage\":\"PARTIAL\"")
                .contains("\"manifestSha256\":\"abc123\"")
                .doesNotContain("manifestJson", "secretInternalObjectKey", "createdBy");
    }

    @Test
    void publicationRequiresExplicitSecondaryConfirmation() {
        var request = new ReleaseController.CreateReleaseRequest("v1.0.0", List.of(UUID.randomUUID()),
                "发布", false, null);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("confirmed"));
        }
    }

    @Test
    void documentFinalizeFieldKeepsThePublicOpenApiName() throws Exception {
        var request = new DocumentController.SaveDocumentRequest(UUID.randomUUID(), "# 已定稿", "业务复核", true);

        String json = new ObjectMapper().writeValueAsString(request);

        assertThat(json).contains("\"finalize\":true").doesNotContain("finalizeRevision");
    }
}
