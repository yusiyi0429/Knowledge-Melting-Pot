package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.ContextBudget;
import com.knowledgemeltingpot.workbench.application.port.EvaluationRepository;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.SkillEvaluationWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.TrustedContext;
import com.knowledgemeltingpot.workbench.application.service.MaterialSelectionService;
import com.knowledgemeltingpot.workbench.domain.EvaluationCase;
import com.knowledgemeltingpot.workbench.domain.EvaluationCaseResult;
import com.knowledgemeltingpot.workbench.domain.EvaluationOutcome;
import com.knowledgemeltingpot.workbench.domain.EvaluationRun;
import com.knowledgemeltingpot.workbench.domain.EvaluationStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.worker.JobHandler;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Resumable, release-bound exact-match evaluation over server-selected Holdout chunks. */
@Component
@ConditionalOnExpression("'${workbench.agent.enabled:false}' == 'true' or "
        + "'${workbench.agent.test-stub-enabled:false}' == 'true' or "
        + "'${workbench.skill-sandbox.enabled:false}' == 'true'")
public class EvaluationJobHandler implements JobHandler {
    private static final int MAX_CASES = 500;
    private static final int MAX_TOTAL_INPUT_CHARS = 500_000;
    private static final Set<String> CASE_FIELDS = Set.of("caseId", "input", "expected", "tags");
    private final EvaluationRepository evaluations;
    private final MaterialSelectionService materials;
    private final SkillEvaluationWorkflowPort workflow;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvaluationJobHandler(EvaluationRepository evaluations, MaterialSelectionService materials,
            SkillEvaluationWorkflowPort workflow, ObjectMapper objectMapper, Clock clock) {
        this.evaluations = evaluations;
        this.materials = materials;
        this.workflow = workflow;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.EVALUATE;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) {
        UUID runId = leasedJob.job().aggregateId();
        EvaluationRun run = evaluations.find(runId).orElse(null);
        if (run == null || !run.jobId().equals(leasedJob.job().id())) {
            return JobHandlingResult.failure("EVALUATION_SNAPSHOT_MISSING",
                    "The frozen evaluation snapshot is unavailable");
        }
        if (run.status() == EvaluationStatus.SUCCEEDED) {
            return JobHandlingResult.success("evaluation-run:" + runId);
        }
        if (!evaluations.markRunning(runId, leasedJob.job().id(), Instant.now(clock))) {
            return JobHandlingResult.failure("EVALUATION_STATE_INVALID", "Evaluation cannot enter RUNNING state");
        }
        try {
            context.progress(5, "evaluation-holdout-loading");
            List<EvaluationCase> cases = evaluations.findCases(runId);
            if (cases.isEmpty()) {
                List<TrustedContext> trusted = materials.evaluationContext(run.roundId(), run.subSceneId(),
                        new ContextBudget(ContextBudget.MAX_TOP_K, MAX_TOTAL_INPUT_CHARS));
                if (trusted.isEmpty()) return fail(runId, "HOLDOUT_REQUIRED");
                cases = parseCases(run, trusted);
                if (cases.isEmpty()) return fail(runId, "HOLDOUT_CASES_EMPTY");
                evaluations.insertCaseSet(runId, caseSetHash(cases), cases, Instant.now(clock));
                cases = evaluations.findCases(runId);
            }

            Set<UUID> completed = new HashSet<>();
            evaluations.findResults(runId).forEach(result -> completed.add(result.caseId()));
            for (int index = 0; index < cases.size(); index++) {
                if (context.cancellationRequested()) {
                    evaluations.markCancelled(runId, Instant.now(clock));
                    return JobHandlingResult.failure("EVALUATION_CANCELLED", "Evaluation was cancelled");
                }
                EvaluationCase evaluationCase = cases.get(index);
                if (!completed.contains(evaluationCase.id())) {
                    long started = System.nanoTime();
                    SkillEvaluationWorkflowPort.EvaluationPrediction result = workflow.predict(
                            new SkillEvaluationWorkflowPort.EvaluationRequest(runId, evaluationCase.id(),
                                    run.modelConfigVersionId(), run.skillVersionId(), evaluationCase.caseKey(),
                                    evaluationCase.input()));
                    long latency = Math.min(3_600_000,
                            Math.max(0, (System.nanoTime() - started) / 1_000_000));
                    String prediction = result.prediction();
                    EvaluationOutcome outcome = normalized(prediction).equals(normalized(evaluationCase.expected()))
                            ? EvaluationOutcome.PASSED : EvaluationOutcome.FAILED;
                    evaluations.insertResult(new EvaluationCaseResult(runId, evaluationCase.id(), prediction,
                            outcome, "", latency, Instant.now(clock)));
                }
                int percent = 15 + (int) (((index + 1) * 80.0) / cases.size());
                context.progress(percent, "evaluation-case-completed");
            }
            EvaluationRepository.EvaluationCounts counts = evaluations.counts(runId);
            if (counts.total() == 0 || counts.passed() + counts.failed() + counts.errors() != counts.total()) {
                return fail(runId, "EVALUATION_RESULTS_INCOMPLETE");
            }
            BigDecimal accuracy = BigDecimal.valueOf(counts.passed())
                    .divide(BigDecimal.valueOf(counts.total()), 6, RoundingMode.HALF_UP);
            if (!evaluations.markSucceeded(runId, counts, accuracy, Instant.now(clock))) {
                return JobHandlingResult.failure("EVALUATION_STATE_RACE", "Evaluation state changed");
            }
            context.progress(99, "evaluation-metrics-persisted");
            return JobHandlingResult.success("evaluation-run:" + runId);
        } catch (EvaluationWorkflowException exception) {
            return fail(runId, exception.code());
        } catch (HoldoutFormatException exception) {
            return fail(runId, exception.code);
        } catch (RuntimeException exception) {
            return fail(runId, "EVALUATION_RUNTIME_ERROR");
        }
    }

    private List<EvaluationCase> parseCases(EvaluationRun run, List<TrustedContext> trusted) {
        List<EvaluationCase> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        int totalChars = 0;
        Instant now = Instant.now(clock);
        for (TrustedContext context : trusted) {
            for (var chunk : context.chunks()) {
                String[] lines = chunk.content().split("\\R", -1);
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    JsonNode node;
                    try {
                        node = objectMapper.readTree(line);
                    } catch (JsonProcessingException exception) {
                        throw new HoldoutFormatException("HOLDOUT_JSONL_INVALID");
                    }
                    if (node == null || !node.isObject()) throw new HoldoutFormatException("HOLDOUT_SCHEMA_INVALID");
                    node.fieldNames().forEachRemaining(field -> {
                        if (!CASE_FIELDS.contains(field)) throw new HoldoutFormatException("HOLDOUT_SCHEMA_INVALID");
                    });
                    String caseKey = text(node, "caseId", 120);
                    String input = text(node, "input", 20_000);
                    String expected = text(node, "expected", 500);
                    List<String> tags = tags(node.path("tags"));
                    if (!keys.add(caseKey)) throw new HoldoutFormatException("HOLDOUT_CASE_DUPLICATE");
                    totalChars += input.length();
                    if (result.size() >= MAX_CASES || totalChars > MAX_TOTAL_INPUT_CHARS) {
                        throw new HoldoutFormatException("HOLDOUT_BUDGET_EXCEEDED");
                    }
                    String hash = sha256(caseKey + "\n" + input + "\n" + expected + "\n"
                            + context.selection().material().id() + "\n" + chunk.id());
                    UUID caseId = UUID.nameUUIDFromBytes((run.id() + ":" + caseKey)
                            .getBytes(StandardCharsets.UTF_8));
                    result.add(new EvaluationCase(caseId, run.id(), result.size(), caseKey, input, expected,
                            context.selection().material().id(), chunk.id(), chunk.sourceRefCode(), hash, tags, now));
                }
            }
        }
        return List.copyOf(result);
    }

    private String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw new HoldoutFormatException("HOLDOUT_SCHEMA_INVALID");
        String text = value.asText().strip();
        if (text.isBlank() || text.length() > max) throw new HoldoutFormatException("HOLDOUT_SCHEMA_INVALID");
        return text;
    }

    private List<String> tags(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray() || value.size() > 16) throw new HoldoutFormatException("HOLDOUT_SCHEMA_INVALID");
        List<String> tags = new ArrayList<>();
        value.forEach(tag -> {
            if (!tag.isTextual() || tag.asText().isBlank() || tag.asText().length() > 80) {
                throw new HoldoutFormatException("HOLDOUT_SCHEMA_INVALID");
            }
            tags.add(tag.asText().strip());
        });
        return List.copyOf(tags);
    }

    private String caseSetHash(List<EvaluationCase> cases) {
        return sha256(cases.stream().map(value -> value.ordinal() + ":" + value.contentHash())
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    private String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private JobHandlingResult fail(UUID runId, String code) {
        String safe = code != null && code.matches("[A-Z0-9_:-]{1,100}") ? code : "EVALUATION_FAILED";
        evaluations.markFailed(runId, safe, Instant.now(clock));
        return JobHandlingResult.failure(safe, "Evaluation did not complete");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class HoldoutFormatException extends RuntimeException {
        private final String code;

        HoldoutFormatException(String code) {
            super(code);
            this.code = code;
        }
    }
}
