package com.knowledgemeltingpot.workbench.api.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.JobEvent;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JobEventEnvelopeMapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JobEventEnvelopeMapper mapper = new JobEventEnvelopeMapper(objectMapper);

    @Test
    void mapsOnlyWhitelistedFieldsAndDropsSensitivePayload() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobEvent event = new JobEvent(41, jobId, "progress", """
                {"stage":"MAP","progress":42,"traceId":"trace-123","prompt":"secret",\
                 "credential":"sk-never-return","rawModelError":"provider body"}
                """, Instant.parse("2026-08-03T00:00:00Z"));

        JobEventEnvelopeMapper.MappedEvent mapped = mapper.map(event);
        String json = objectMapper.writeValueAsString(mapped.body());

        assertThat(mapped.name()).isEqualTo("progress");
        assertThat(mapped.body().eventId()).isEqualTo("41");
        assertThat(mapped.body().sequence()).isEqualTo(41);
        assertThat(mapped.body().jobId()).isEqualTo(jobId);
        assertThat(mapped.body().stage()).isEqualTo("MAP");
        assertThat(mapped.body().percent()).isEqualTo(42);
        assertThat(mapped.body().messageCode()).isEqualTo("JOB_PROGRESS");
        assertThat(mapped.body().traceId()).isEqualTo("trace-123");
        assertThat(json).doesNotContain("secret", "sk-never-return", "provider body", "credential", "prompt");
    }

    @Test
    void rejectsUntrustedTokensAndClampsProgress() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobEvent event = new JobEvent(7, jobId, "failed",
                "{\"stage\":\"<script>\",\"progress\":999,\"errorCode\":\"bad code with spaces\","
                        + "\"traceId\":\"\\nheader: injected\"}",
                Instant.parse("2026-08-03T00:00:00Z"));

        JobEventEnvelopeMapper.PublicJobEvent body = mapper.map(event).body();

        assertThat(body.stage()).isEqualTo("FAILED");
        assertThat(body.percent()).isEqualTo(100);
        assertThat(body.messageCode()).isEqualTo("JOB_FAILED");
        assertThat(body.traceId()).isEqualTo("job-" + jobId);
    }

    @ParameterizedTest
    @MethodSource("eventNames")
    void exposesOnlyTheSixDocumentedSseEventNames(String internal, String expected) throws Exception {
        JobEvent event = new JobEvent(1, UUID.randomUUID(), internal, "{}", Instant.EPOCH);
        assertThat(mapper.map(event).name()).isEqualTo(expected);
    }

    private static Stream<Arguments> eventNames() {
        return Stream.of(
                Arguments.of("queued", "stage-started"),
                Arguments.of("started", "stage-started"),
                Arguments.of("progress", "progress"),
                Arguments.of("preview", "preview"),
                Arguments.of("warning", "warning"),
                Arguments.of("cancelled", "warning"),
                Arguments.of("completed", "completed"),
                Arguments.of("failed", "failed"),
                Arguments.of("provider-secret-event", "warning"));
    }
}
