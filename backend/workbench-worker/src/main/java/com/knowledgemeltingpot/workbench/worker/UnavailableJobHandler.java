package com.knowledgemeltingpot.workbench.worker;

import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.domain.JobType;
import org.springframework.stereotype.Component;

@Component
public class UnavailableJobHandler implements JobHandler {
    @Override
    public boolean supports(JobType type) {
        return true;
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) {
        return JobHandlingResult.failure("HANDLER_NOT_CONFIGURED",
                "No configured worker handler is available for " + leasedJob.job().type());
    }
}
