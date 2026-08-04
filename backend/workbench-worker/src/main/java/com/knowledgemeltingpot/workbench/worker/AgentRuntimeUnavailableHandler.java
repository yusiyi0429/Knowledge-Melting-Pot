package com.knowledgemeltingpot.workbench.worker;

import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workbench.agent.enabled", havingValue = "false", matchIfMissing = true)
public class AgentRuntimeUnavailableHandler implements JobHandler {
    private static final Set<JobType> AGENT_JOBS = EnumSet.of(
            JobType.EXTRACT,
            JobType.REEXTRACT,
            JobType.ALIGN,
            JobType.SCENE_EXPLORE,
            JobType.EVALUATE);

    @Override
    public boolean supports(JobType type) {
        return AGENT_JOBS.contains(type);
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) {
        return JobHandlingResult.failure("AGENT_RUNTIME_DISABLED",
                "The Agent runtime is disabled for this Worker deployment");
    }
}
