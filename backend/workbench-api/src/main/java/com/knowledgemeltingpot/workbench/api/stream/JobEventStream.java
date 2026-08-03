package com.knowledgemeltingpot.workbench.api.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.service.JobService;
import com.knowledgemeltingpot.workbench.domain.JobEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class JobEventStream {
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final JobService jobService;
    private final JobEventEnvelopeMapper eventMapper;
    private final ExecutorService sseExecutor;

    public JobEventStream(JobService jobService, ObjectMapper objectMapper, ExecutorService sseExecutor) {
        this.jobService = jobService;
        this.eventMapper = new JobEventEnvelopeMapper(objectMapper);
        this.sseExecutor = sseExecutor;
    }

    public SseEmitter open(UUID jobId, long afterSequence) {
        jobService.get(jobId);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        String requestTraceId = MDC.get("traceId");
        String traceId = requestTraceId == null || requestTraceId.isBlank()
                ? "job-" + jobId
                : requestTraceId;
        sseExecutor.execute(() -> stream(jobId, Math.max(0, afterSequence), emitter, closed, traceId));
        return emitter;
    }

    private void stream(UUID jobId, long cursor, SseEmitter emitter, AtomicBoolean closed, String traceId) {
        Instant lastHeartbeat = Instant.now();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", traceId)) {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                List<JobEvent> events = jobService.eventsAfter(jobId, cursor, 200);
                for (JobEvent event : events) {
                    JobEventEnvelopeMapper.MappedEvent mapped = eventMapper.map(event);
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name(mapped.name())
                            .data(mapped.body(), MediaType.APPLICATION_JSON));
                    cursor = event.sequence();
                }
                if (jobService.get(jobId).status().terminal() && events.isEmpty()) {
                    emitter.complete();
                    return;
                }
                if (Duration.between(lastHeartbeat, Instant.now()).compareTo(HEARTBEAT_INTERVAL) >= 0) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = Instant.now();
                }
                if (events.isEmpty()) {
                    Thread.sleep(500);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException | RuntimeException exception) {
            if (!closed.get()) {
                emitter.completeWithError(exception);
            }
        }
    }
}
