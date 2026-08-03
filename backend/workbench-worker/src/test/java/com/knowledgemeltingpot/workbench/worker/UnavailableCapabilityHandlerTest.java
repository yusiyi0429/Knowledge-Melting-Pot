package com.knowledgemeltingpot.workbench.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnavailableCapabilityHandlerTest {

    @Test
    void materialIngestFailsWithAnExplicitCapabilityCode() {
        MaterialIngestUnavailableHandler handler = new MaterialIngestUnavailableHandler();

        JobHandlingResult result = handler.handle(leased(JobType.INGEST), mock(WorkerJobContext.class));

        assertThat(result.errorCode()).isEqualTo("OBJECT_STORAGE_NOT_CONFIGURED");
        assertThat(handler.order()).isLessThan(Integer.MAX_VALUE);
    }

    @Test
    void disabledAgentRuntimeFailsAgentJobsWithoutClaimingDeterministicJobs() {
        AgentRuntimeUnavailableHandler handler = new AgentRuntimeUnavailableHandler();

        assertThat(handler.supports(JobType.EXTRACT)).isTrue();
        assertThat(handler.supports(JobType.ALIGN)).isTrue();
        assertThat(handler.supports(JobType.GENERATE_ASSET)).isFalse();
        assertThat(handler.handle(leased(JobType.EXTRACT), mock(WorkerJobContext.class)).errorCode())
                .isEqualTo("AGENT_RUNTIME_DISABLED");
    }

    private LeasedJob leased(JobType type) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        Job job = new Job(UUID.randomUUID(), type, "TEST", UUID.randomUUID(), JobStatus.RUNNING,
                1, 1, "{}", "", "", "", UUID.randomUUID(), now, now);
        return new LeasedJob(job, "worker-1", now.plusSeconds(60), 1);
    }
}
