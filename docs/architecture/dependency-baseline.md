# Dependency baseline

## Agent runtime

- Maven coordinate: `com.openjiuwen:agent-core-java:0.1.13`
- Source tag: `v0.1.13`
- Source commit: `830610b673807edeeeac0ff8db0f5433794efdad`
- Central JAR SHA-256: `65561515ace2d44a1f2448d54fcdf2a2e09eadb8ba5be715836975f3fc4d64ea`
- Central POM SHA-256: `2b3e31626879724685bcc08d586093f8a4edb59ad7e116d3ef55d1dab0c030e1`

Only `workbench-agent-adapter` may declare this dependency. CI must fail on unplanned dependency convergence errors. If an upstream defect requires a temporary internal artifact, its patch and checksum must be appended here before use.

## Toolchains

- JDK: 21 LTS
- Maven Wrapper: 3.3.2 using Maven 3.9.11
- Node.js: 24 LTS line
- Package manager: pnpm 11

Exact frontend package versions are locked by the workspace-level `pnpm-lock.yaml`. Backend dependency versions are locked by the Maven parent and imported BOMs.
