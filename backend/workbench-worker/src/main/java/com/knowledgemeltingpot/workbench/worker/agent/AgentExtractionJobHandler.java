package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionEventType;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.agent.KnowledgeExtractionPort;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.worker.JobHandler;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(KnowledgeExtractionPort.class)
public class AgentExtractionJobHandler implements JobHandler {
    private final KnowledgeExtractionPort extractionPort;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper;

    public AgentExtractionJobHandler(KnowledgeExtractionPort extractionPort, DocumentService documentService,
            ObjectMapper objectMapper) {
        this.extractionPort = extractionPort;
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.EXTRACT || type == JobType.REEXTRACT;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) throws Exception {
        String prompt = promptFrom(leasedJob.job().payloadJson());
        if (prompt.isBlank()) {
            return JobHandlingResult.failure("MATERIAL_CONTEXT_NOT_READY",
                    "Verified source material is not available for extraction");
        }
        AgentExecutionRequest request = new AgentExecutionRequest(
                leasedJob.job().aggregateId().toString(),
                leasedJob.job().requestedBy().toString(),
                prompt,
                AgentExecutionMode.WORKFLOW);
        AtomicInteger lastProgress = new AtomicInteger(1);
        AgentExecutionResult result = extractionPort.stream(request, event -> {
            int progress = progressFor(event.type());
            if (progress > lastProgress.getAndSet(progress)) {
                context.progress(progress, event.type().name());
            }
            if (context.cancellationRequested()) {
                throw new JobCancellationException();
            }
        });
        if (result.status() != AgentExecutionStatus.COMPLETED) {
            return JobHandlingResult.failure(result.failureCode().isBlank() ? result.status().name() : result.failureCode(),
                    "Agent extraction did not complete");
        }
        DocumentRevision current = currentRevision(leasedJob.job().aggregateId());
        DocumentRevision saved = documentService.save(
                leasedJob.job().aggregateId(),
                leasedJob.job().aggregateId(),
                result.output(),
                current == null ? null : current.etag(),
                leasedJob.job().requestedBy(),
                "worker:" + leasedJob.job().id());
        return JobHandlingResult.success("knowledge-document:" + saved.documentId() + ":revision:" + saved.revision());
    }

    private DocumentRevision currentRevision(java.util.UUID documentId) {
        try {
            return documentService.get(documentId);
        } catch (NotFoundException exception) {
            return null;
        }
    }

    private String promptFrom(String payloadJson) throws Exception {
        JsonNode payload = objectMapper.readTree(payloadJson);
        String sourceText = payload.path("trustedSourceContext").path("text").asText("");
        if (sourceText.isBlank()) {
            return "";
        }
        if (sourceText.length() > 1_000_000) {
            throw new IllegalArgumentException("trusted source context exceeds the processing budget");
        }
        return "仅根据以下已验证来源萃取结构化知识；不得补充来源外事实，并保留来源定位：\n" + sourceText;
    }

    private int progressFor(AgentExecutionEventType type) {
        return switch (type) {
            case STARTED -> 5;
            case TOOL_ACTIVITY -> 35;
            case TEXT_DELTA -> 60;
            case PROGRESS -> 70;
            case INPUT_REQUIRED -> 75;
            case COMPLETED -> 95;
            case FAILED, CANCELLED -> 90;
        };
    }

    private static final class JobCancellationException extends RuntimeException {
    }
}
