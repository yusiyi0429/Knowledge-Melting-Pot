package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobResponseContractTest {

    @Test
    void publicResponseDoesNotExposePayloadOwnershipOrRawError() throws Exception {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        Job job = new Job(UUID.randomUUID(), JobType.EXTRACT, "SUB_SCENE", UUID.randomUUID(),
                JobStatus.FAILED, 64, 2, "{\"prompt\":\"secret source\"}", "s3://private/result",
                "MODEL_TIMEOUT", "raw upstream response containing a credential", UUID.randomUUID(), now, now);

        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(JobController.JobResponse.from(job));

        assertThat(json).contains("\"attempt\":2", "\"percent\":64", "MODEL_TIMEOUT");
        assertThat(json).doesNotContain("secret source", "credential", "payloadJson", "requestedBy",
                "aggregateId", "resultReference", "errorMessage");
    }
}
