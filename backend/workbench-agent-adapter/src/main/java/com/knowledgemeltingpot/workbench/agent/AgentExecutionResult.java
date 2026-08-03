package com.knowledgemeltingpot.workbench.agent;

import java.time.Instant;
import java.util.Objects;

/** Stable terminal result; no openJiuwen value type crosses this boundary. */
public record AgentExecutionResult(
        String jobId,
        String sessionId,
        AgentExecutionStatus status,
        String output,
        String failureCode,
        String failureMessage,
        Instant startedAt,
        Instant completedAt) {

    public AgentExecutionResult {
        jobId = requireText(jobId, "jobId");
        sessionId = requireText(sessionId, "sessionId");
        status = Objects.requireNonNull(status, "status must not be null");
        output = output == null ? "" : output;
        failureCode = failureCode == null ? "" : failureCode;
        failureMessage = failureMessage == null ? "" : failureMessage;
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Omits model output and failure details from incidental logs. */
    @Override
    public String toString() {
        return "AgentExecutionResult[jobId=" + jobId
                + ", sessionId=" + sessionId
                + ", status=" + status
                + ", outputLength=" + output.length()
                + ", failureCode=" + failureCode
                + ", startedAt=" + startedAt
                + ", completedAt=" + completedAt + "]";
    }
}
