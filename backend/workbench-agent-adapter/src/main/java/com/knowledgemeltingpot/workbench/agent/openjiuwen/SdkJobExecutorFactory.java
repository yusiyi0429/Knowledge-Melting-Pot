package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;

@FunctionalInterface
interface SdkJobExecutorFactory {
    SdkJobExecutor create(AgentExecutionRequest request, String jobId, String sessionId);
}
