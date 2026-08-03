package com.knowledgemeltingpot.workbench.agent;

import java.util.Objects;

/**
 * Business request for one extraction job.
 *
 * <p>Job and session identifiers are deliberately absent: the runtime factory
 * creates both on the server for every job.</p>
 */
public record AgentExecutionRequest(
        String workspaceId,
        String actorId,
        String prompt,
        AgentExecutionMode executionMode) {

    public AgentExecutionRequest {
        workspaceId = requireText(workspaceId, "workspaceId");
        actorId = requireText(actorId, "actorId");
        prompt = requireText(prompt, "prompt");
        executionMode = Objects.requireNonNull(executionMode, "executionMode must not be null");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    /** Omits the prompt so accidental object logging cannot disclose source material. */
    @Override
    public String toString() {
        return "AgentExecutionRequest[workspaceId=" + workspaceId
                + ", actorId=" + actorId
                + ", promptLength=" + prompt.length()
                + ", executionMode=" + executionMode + "]";
    }
}
