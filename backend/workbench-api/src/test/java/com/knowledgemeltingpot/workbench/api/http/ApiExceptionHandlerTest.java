package com.knowledgemeltingpot.workbench.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgemeltingpot.workbench.application.error.PayloadTooLargeException;
import com.knowledgemeltingpot.workbench.application.error.EmbeddingProviderException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.BadCredentialsException;

class ApiExceptionHandlerTest {
    @Test
    void applicationProblemsContainStableCodeAndTraceId() {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", "trace-456")) {
            var response = new ApiExceptionHandler().badRequest(new IllegalArgumentException("invalid value"));

            assertThat(response.getBody().getProperties())
                    .containsEntry("code", "bad-request")
                    .containsEntry("traceId", "trace-456");
        }
    }

    @Test
    void authenticationFailureDoesNotExposeProviderDetail() {
        var response = new ApiExceptionHandler().authentication(new BadCredentialsException("provider details"));

        assertThat(response.getBody().getDetail()).isEqualTo("Invalid username or password");
        assertThat(response.getBody().getProperties()).containsEntry("code", "authentication-failed");
    }

    @Test
    void materialHardLimitUsesPayloadTooLargeProblem() {
        var response = new ApiExceptionHandler().payloadTooLarge(
                new PayloadTooLargeException("material exceeds the 200MB upload limit"));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().getProperties()).containsEntry("code", "material-too-large");
    }

    @Test
    void embeddingFailureUsesStableSanitizedProblemWithoutProviderPayload() {
        var response = new ApiExceptionHandler().embeddingProvider(
                new EmbeddingProviderException("EMBEDDING_RATE_LIMITED", true));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getDetail())
                .isEqualTo("The configured embedding Provider could not complete the request");
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "embedding-rate-limited")
                .containsEntry("retryable", true);
    }
}
