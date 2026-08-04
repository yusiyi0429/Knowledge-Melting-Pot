package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.DenseRetrievalResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class EmbeddingControllerContractTest {
    @Test
    void responseKeepsSourceLocatorAndBoundsTheExcerpt() throws Exception {
        String content = "风".repeat(600);
        DenseRetrievalResult result = new DenseRetrievalResult(UUID.randomUUID(), UUID.randomUUID(),
                "SRC-risk-0", new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES,
                        null, null, null, null, null, null, null, null, 1, 3), content, 0.91);

        EmbeddingController.DenseRetrievalResponse response =
                EmbeddingController.DenseRetrievalResponse.from(result);
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(response.excerpt()).hasSize(501).endsWith("…");
        assertThat(json).contains("\"sourceRefCode\":\"SRC-risk-0\"", "\"lineStart\":1")
                .doesNotContain(content);
    }

    @Test
    void profileMutationIsAdminOnlyWhileRetrievalUsesAuthenticatedSessionScope() throws Exception {
        PreAuthorize list = EmbeddingController.class.getMethod("listProfiles")
                .getAnnotation(PreAuthorize.class);
        PreAuthorize create = EmbeddingController.class.getMethod("createProfile",
                EmbeddingController.CreateEmbeddingProfileRequest.class,
                org.springframework.security.core.Authentication.class)
                .getAnnotation(PreAuthorize.class);
        PreAuthorize retrieve = EmbeddingController.class.getMethod("retrieve",
                UUID.class, UUID.class, String.class, int.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(list.value()).isEqualTo("hasRole('ADMIN')");
        assertThat(create.value()).isEqualTo("hasRole('ADMIN')");
        assertThat(retrieve).isNull();
    }
}
