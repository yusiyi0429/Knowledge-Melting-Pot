package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.openjiuwen.core.session.WorkflowSessionApi;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.WorkflowChunk;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.core.workflow.component.End;
import com.openjiuwen.core.workflow.component.Start;
import com.openjiuwen.core.workflow.component.llm.LLMCompConfig;
import com.openjiuwen.core.workflow.component.llm.LLMComponent;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class WorkflowSdkJobExecutor implements SdkJobExecutor {
    private final AgentModelConfiguration modelConfiguration;
    private final AgentExecutionRequest request;
    private final String jobId;
    private final String sessionId;
    private final AtomicReference<Iterator<?>> activeStream = new AtomicReference<>();

    WorkflowSdkJobExecutor(
            AgentModelConfiguration modelConfiguration,
            AgentExecutionRequest request,
            String jobId,
            String sessionId) {
        this.modelConfiguration = modelConfiguration;
        this.request = request;
        this.jobId = jobId;
        this.sessionId = sessionId;
    }

    @Override
    public SdkTerminalResult execute() {
        Workflow workflow = createWorkflow(false);
        WorkflowOutput output = workflow.invoke(
                Map.of("query", request.prompt()),
                new WorkflowSessionApi(sessionId),
                null);
        return SdkResultMapper.fromWorkflow(output);
    }

    @Override
    public Iterator<?> stream() {
        Workflow workflow = createWorkflow(true);
        Iterator<WorkflowChunk> iterator = workflow.stream(
                Map.of("query", request.prompt()),
                new WorkflowSessionApi(sessionId),
                null,
                List.of(StreamMode.OUTPUT));
        activeStream.set(iterator);
        return iterator;
    }

    @Override
    public void cancel() {
        Iterator<?> iterator = activeStream.getAndSet(null);
        if (iterator instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Workflow 0.1.13 normally returns a plain Iterator; interruption is handled by the lifecycle.
            }
        }
    }

    @Override
    public void close() {
        cancel();
    }

    private Workflow createWorkflow(boolean streaming) {
        WorkflowCard card = WorkflowCard.builder()
                .id("workflow-" + jobId)
                .name("knowledge-extraction-workflow")
                .description("Controlled single-stage knowledge extraction workflow")
                .build();
        Workflow workflow = new Workflow(card);

        LLMCompConfig llmConfig = new LLMCompConfig();
        llmConfig.setModelClientConfig(
                SdkModelConfigurationMapper.clientConfiguration(modelConfiguration, jobId));
        llmConfig.setModelConfig(SdkModelConfigurationMapper.requestConfiguration(modelConfiguration));
        llmConfig.setTemplateContent(List.of(
                Map.of("role", "system", "content", modelConfiguration.systemPrompt()),
                Map.of("role", "user", "content", "{{query}}")));
        llmConfig.setResponseFormat(Map.of("type", "text"));
        llmConfig.setOutputConfig(Map.of(
                "answer", Map.of(
                        "type", "string",
                        "description", "知识萃取结果",
                        "required", true)));
        llmConfig.setCacheStream(streaming);

        workflow.setStartComp("start", new Start(), Map.of("query", "${query}"), null);
        workflow.addWorkflowComp(
                "extract",
                new LLMComponent(llmConfig),
                Map.of("query", "${start.query}"),
                null);

        if (streaming) {
            workflow.setEndComp(
                    "end",
                    new End(Map.of("responseTemplate", "{{answer}}")),
                    null,
                    null,
                    Map.of("answer", "${extract.answer}"),
                    null,
                    "streaming");
            workflow.addConnection("start", "extract");
            workflow.addStreamConnection("extract", "end");
        } else {
            workflow.setEndComp(
                    "end",
                    new End(),
                    Map.of("answer", "${extract.answer}"),
                    null);
            workflow.addConnection("start", "extract");
            workflow.addConnection("extract", "end");
        }
        return workflow;
    }
}
