package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.KnowledgeExtractionPort;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentExtractionJobHandlerTest {

    @Test
    void refusesToGenerateWhenVerifiedMaterialContextIsMissing() throws Exception {
        KnowledgeExtractionPort extractionPort = mock(KnowledgeExtractionPort.class);
        AgentExtractionJobHandler handler = new AgentExtractionJobHandler(extractionPort,
                mock(DocumentService.class), new ObjectMapper());
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        Job job = new Job(UUID.randomUUID(), JobType.EXTRACT, "SUB_SCENE", UUID.randomUUID(),
                JobStatus.RUNNING, 1, 1,
                "{\"roundId\":\"00000000-0000-0000-0000-000000000001\"}",
                "", "", "", UUID.randomUUID(), now, now);
        LeasedJob leasedJob = new LeasedJob(job, "worker-1", now.plusSeconds(60), 1);

        JobHandlingResult result = handler.handle(leasedJob, mock(WorkerJobContext.class));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MATERIAL_CONTEXT_NOT_READY");
        verifyNoInteractions(extractionPort);
    }
}
