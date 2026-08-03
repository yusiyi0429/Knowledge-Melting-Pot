# Workbench Agent Adapter

This module is the only backend module allowed to depend on
`com.openjiuwen:agent-core-java:0.1.13`. It translates the SDK's dynamic
`Map`/`Object` contracts and blocking iterators into stable workbench records,
enums and ports.

## Public boundary

- `com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest`
- `com.knowledgemeltingpot.workbench.agent.AgentExecutionResult`
- `com.knowledgemeltingpot.workbench.agent.AgentExecutionEvent`
- `com.knowledgemeltingpot.workbench.agent.AgentRuntimeLifecycle`
- `com.knowledgemeltingpot.workbench.agent.AgentRuntimeFactory`
- `com.knowledgemeltingpot.workbench.agent.KnowledgeExtractionPort`
- `com.knowledgemeltingpot.workbench.agent.DefaultKnowledgeExtractionAdapter`
- `com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration`
- `com.knowledgemeltingpot.workbench.agent.ModelProvider`
- `com.knowledgemeltingpot.workbench.agent.openjiuwen.OpenJiuwenAgentRuntimeFactory`

`AgentExecutionRequest` does not accept a job ID or session ID. The factory
creates fresh, unguessable identifiers and a fresh SDK Agent/Workflow instance
for every job. A runtime is one-shot and must be closed.

The only exposed execution modes are `REACT` and `WORKFLOW`. ReAct starts with
no arbitrary tools, while Workflow is a fixed single-stage knowledge extraction
graph. Extending either path should happen inside this module, not by exposing
SDK types to callers.

## Integration

The backend aggregator must include:

```xml
<module>workbench-agent-adapter</module>
```

Consumers use:

```xml
<dependency>
  <groupId>com.knowledgemeltingpot</groupId>
  <artifactId>workbench-agent-adapter</artifactId>
  <version>${project.version}</version>
</dependency>
```

Construct `AgentModelConfiguration` from server-side secret configuration and
inject `OpenJiuwenAgentRuntimeFactory` into `DefaultKnowledgeExtractionAdapter`.
Never put API keys in a request DTO. Streaming is intentionally blocking; a
worker or transport adapter should consume it on a virtual thread or a bounded
executor, forwarding `AgentExecutionEvent` values to SSE or persistence.

The adapter raises sensitive openJiuwen logger categories to WARN because SDK
INFO messages include prompts, responses and tool arguments. Application-level
logging configuration should preserve that minimum level.

## Verification

No real model key is required:

```bash
./mvnw -f backend/pom.xml -pl workbench-agent-adapter -am test
```

The dependency probe fails unless the resolved artifact is exactly `0.1.13`
and the required ReAct, Workflow, stream and close APIs remain present.
