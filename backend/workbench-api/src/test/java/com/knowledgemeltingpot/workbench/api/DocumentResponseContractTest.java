package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentResponseContractTest {

    @Test
    void currentDocumentResponseUsesTheOpenApiProjectionOnly() throws Exception {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        UUID actorId = UUID.randomUUID();
        String content = "# 规则\n\n结论 [SRC-001]";
        DocumentRevision revision = new DocumentRevision(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                3, UUID.randomUUID(), content, "a".repeat(64), "复核", true, actorId, now,
                actorId, now);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        String json = objectMapper.writeValueAsString(DocumentController.KnowledgeDocumentResponse.from(revision));

        assertThat(json).contains("contentMd", "revisionNumber", "sourceRefs");
        assertThat(objectMapper.readTree(json).path("etag").asText()).isEqualTo(revision.etag());
        assertThat(json).doesNotContain("baseRevisionId", "createdBy", "finalizedBy", "finalizedAt",
                "revisionNote");
    }
}
