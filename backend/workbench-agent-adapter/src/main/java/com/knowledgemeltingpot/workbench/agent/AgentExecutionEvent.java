package com.knowledgemeltingpot.workbench.agent;

import java.time.Instant;
import java.util.Objects;

/** One ordered business event produced while consuming an SDK blocking stream. */
public record AgentExecutionEvent(
        String jobId,
        String sessionId,
        long sequence,
        AgentExecutionEventType type,
        String text,
        String code,
        Instant occurredAt) {

    public AgentExecutionEvent {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        type = Objects.requireNonNull(type, "type must not be null");
        text = text == null ? "" : text;
        code = code == null ? "" : code;
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    /** Omits event text because it can contain source or model content. */
    @Override
    public String toString() {
        return "AgentExecutionEvent[jobId=" + jobId
                + ", sessionId=" + sessionId
                + ", sequence=" + sequence
                + ", type=" + type
                + ", textLength=" + text.length()
                + ", code=" + code
                + ", occurredAt=" + occurredAt + "]";
    }
}
