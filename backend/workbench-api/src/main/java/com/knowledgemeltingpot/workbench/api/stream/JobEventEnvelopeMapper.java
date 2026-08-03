package com.knowledgemeltingpot.workbench.api.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.JobEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class JobEventEnvelopeMapper {
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SAFE_STAGE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ObjectMapper objectMapper;

    JobEventEnvelopeMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    MappedEvent map(JobEvent event) throws JsonProcessingException {
        JsonNode payload = objectMapper.readTree(event.payloadJson());
        String internalType = event.eventType().toLowerCase(Locale.ROOT);
        String eventName = publicEventName(internalType);
        String stage = safeStage(payload.path("stage").asText(""), defaultStage(internalType));
        int percent = percent(payload, internalType);
        String messageCode = messageCode(payload, internalType);
        String traceId = safeToken(payload.path("traceId").asText(""), "job-" + event.jobId());

        PublicJobEvent body = new PublicJobEvent(
                Long.toString(event.sequence()),
                event.sequence(),
                event.jobId(),
                stage,
                percent,
                messageCode,
                traceId,
                event.occurredAt(),
                defaultMessage(internalType));
        return new MappedEvent(eventName, body);
    }

    private String publicEventName(String internalType) {
        return switch (internalType) {
            case "queued", "started" -> "stage-started";
            case "progress" -> "progress";
            case "preview" -> "preview";
            case "warning", "cancelled" -> "warning";
            case "completed" -> "completed";
            case "failed" -> "failed";
            default -> "warning";
        };
    }

    private String defaultStage(String internalType) {
        return switch (internalType) {
            case "queued" -> "QUEUED";
            case "started" -> "STARTING";
            case "progress" -> "PROCESSING";
            case "preview" -> "PREVIEW";
            case "cancelled" -> "CANCELLED";
            case "completed" -> "COMPLETED";
            case "failed" -> "FAILED";
            default -> "WARNING";
        };
    }

    private int percent(JsonNode payload, String internalType) {
        JsonNode value = payload.has("percent") ? payload.path("percent") : payload.path("progress");
        int fallback = switch (internalType) {
            case "started" -> 1;
            case "completed" -> 100;
            default -> 0;
        };
        int percent = value.canConvertToInt() ? value.asInt() : fallback;
        return Math.max(0, Math.min(100, percent));
    }

    private String messageCode(JsonNode payload, String internalType) {
        String candidate = payload.path("messageCode").asText("");
        if (candidate.isBlank() && "failed".equals(internalType)) {
            candidate = payload.path("errorCode").asText("");
        }
        String fallback = switch (internalType) {
            case "queued" -> payload.path("retry").asBoolean(false) ? "JOB_RETRY_QUEUED" : "JOB_QUEUED";
            case "started" -> "JOB_STARTED";
            case "progress" -> "JOB_PROGRESS";
            case "preview" -> "JOB_PREVIEW";
            case "cancelled" -> "JOB_CANCELLED";
            case "completed" -> "JOB_COMPLETED";
            case "failed" -> "JOB_FAILED";
            default -> "JOB_WARNING";
        };
        return safeToken(candidate, fallback);
    }

    private String defaultMessage(String internalType) {
        return switch (internalType) {
            case "queued" -> "Job queued";
            case "started" -> "Job started";
            case "progress" -> "Job progress updated";
            case "preview" -> "A redacted preview is available";
            case "cancelled" -> "Job cancelled";
            case "completed" -> "Job completed";
            case "failed" -> "Job failed";
            default -> "Job warning";
        };
    }

    private String safeToken(String value, String fallback) {
        return SAFE_TOKEN.matcher(value).matches() ? value : fallback;
    }

    private String safeStage(String value, String fallback) {
        return SAFE_STAGE.matcher(value).matches() ? value : fallback;
    }

    record MappedEvent(String name, PublicJobEvent body) {
    }

    record PublicJobEvent(
            String eventId,
            long sequence,
            UUID jobId,
            String stage,
            int percent,
            String messageCode,
            String traceId,
            Instant timestamp,
            String message) {
    }
}
