package com.knowledgemeltingpot.workbench.worker;

import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.domain.JobType;

public interface JobHandler {
    boolean supports(JobType type);

    default int order() {
        return 0;
    }

    JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) throws Exception;
}
