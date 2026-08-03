# Architecture decisions

## Runtime boundary

`agent-core-java` is an Agent runtime SDK, not the HTTP or business framework. The application owns authentication, authorization, persistence, jobs, object storage, audit and release snapshots. Only the Worker-side adapter imports the SDK.

The baseline is the immutable `v0.1.13` tag (`830610b673807edeeeac0ff8db0f5433794efdad`). CI must resolve or internally mirror this artifact; developer-local `mvn install` is not a build source.

## Durable work

Long-running commands create PostgreSQL-backed Jobs. Workers claim leases with `FOR UPDATE SKIP LOCKED`, renew heartbeats and persist ordered JobEvents before exposing them over SSE. Agent state is recreated from application checkpoints; SDK in-memory checkpointers are not business durability.

## Knowledge truth and lineage

Every saved Markdown document creates an immutable revision, and generated assets bind to exactly one document revision. A validated `KnowledgeIR` projection with stable rule and flow IDs, plus fully resolved page/paragraph/sheet/line SourceRefs, remains the required next business slice; the current foundation does not claim that projection is already generated.

## Publication

Publishing creates a cumulative immutable Scene snapshot. Selected subscenes must be complete; unselected subscenes carry forward the last published revisions. Missing subscenes remain explicit in the manifest. Credentials are never included.

## Skills and evaluation

Skill packages are versioned prompts and resources in the first release. Executable content remains disabled until it can run in an isolated, resource-limited sandbox. Evaluation data marked `LABELED_HOLDOUT` is inaccessible to extraction and QA workflows.
