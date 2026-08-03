package com.knowledgemeltingpot.workbench.worker;

import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.domain.JobType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workbench.material-ingest.enabled", havingValue = "false", matchIfMissing = true)
public class MaterialIngestUnavailableHandler implements JobHandler {
    @Override
    public boolean supports(JobType type) {
        return type == JobType.INGEST;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) {
        return JobHandlingResult.failure("OBJECT_STORAGE_NOT_CONFIGURED",
                "Material validation is unavailable until the object-storage ingest adapter is configured");
    }
}
