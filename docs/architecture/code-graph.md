# Source-derived CodeGraph

This graph is intentionally limited to the Maven module boundary and the
`agent-core-java` adapter touchpoints. It is derived from the module POMs and
Java imports; `target`, `dist`, `node_modules`, `output`, product source files,
and generated artifacts are excluded. No runtime CodeGraph dependency is used.

```mermaid
flowchart LR
    WEB["frontend · React/Vite"] -->|"REST + SSE /api/v1"| API["workbench-api"]
    API --> APP["workbench-application"]
    API --> PERSIST["workbench-persistence"]
    WORKER["workbench-worker"] --> APP
    WORKER --> PERSIST
    WORKER --> ADAPTER["workbench-agent-adapter"]
    PERSIST --> APP
    APP --> DOMAIN["workbench-domain"]
    ADAPTER --> CORE["com.openjiuwen:agent-core-java:0.1.13"]
```

## Adapter touchpoints

```mermaid
flowchart TD
    PORT["KnowledgeExtractionPort"] --> DEFAULT["DefaultKnowledgeExtractionAdapter"]
    DEFAULT --> FACTORY["OpenJiuwenAgentRuntimeFactory"]
    FACTORY --> RUNTIME["OpenJiuwenAgentRuntime · one instance per Job"]
    RUNTIME --> MODE{"Execution mode"}
    MODE -->|"deterministic"| WORKFLOW["WorkflowSdkJobExecutor"]
    MODE -->|"bounded iteration"| REACT["ReactSdkJobExecutor"]
    WORKFLOW --> SDKW["agent-core Workflow / WorkflowSessionApi"]
    REACT --> SDKR["agent-core ReActAgent / AgentSessionApi"]
    RUNTIME --> MAPPER["SdkStreamEventMapper + SdkResultMapper"]
    MAPPER --> REDACT["SensitiveTextRedactor"]
    PROBE["AgentCoreDependencyProbe"] --> SDKW
    PROBE --> SDKR
```

Public adapter contracts contain typed records and enums only. SDK `Map` and
`Object` values terminate inside `workbench-agent-adapter`; the API, domain,
application, and persistence modules must not import `com.openjiuwen.*`. The
boundary is enforced by `scripts/validate-code-boundaries.sh` and adapter
contract tests.
