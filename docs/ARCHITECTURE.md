# Architecture

This repository is a **Jenkins plugin** (`io.jenkins.plugins:slurm`, packaging `hpi`) that
provisions dynamic Jenkins agents as Slurm batch jobs via `slurmrestd`. It is analogous to the
[Kubernetes plugin](https://plugins.jenkins.io/kubernetes/): the controller submits work to an
external orchestrator, waits for an inbound agent connection, runs builds, then tears down the job.

## Stack

| Layer | Technology |
| --- | --- |
| Runtime | Java 17/21, Jenkins baseline `2.504.3` |
| Build | Maven (`org.jenkins-ci.plugins:plugin:5.21`), Spotless, JUnit 5 |
| Code generation | OpenAPI Generator 7.0.1 → Slurm REST client from `src/main/resources/openapi/slurm-v0.0.42.json` |
| External integration | Slurm REST API (`slurmrestd`, JWT via `X-Slurm-USER-TOKEN`) |
| Optional integration | Pyxis/Enroot containers (`PyxisConfig`) |
| UI | Jelly templates + `Messages.properties` under `src/main/resources/io/jenkins/plugins/slurm/` |
| Reference patterns | `../kubernetes` (Kubernetes plugin) |

## Provisioning Flow

```text
Build needs agent (label "gpu")
  → SlurmCloud.provision(label)
  → SlurmJobTemplateUtils.getTemplateByLabel()
  → SlurmJobBuilder.build()              # template → v0.0.42_job_desc_msg + JNLP script
  → SlurmClient.submitJob()              # POST to slurmrestd
  → Slurm launches job on compute node
  → Job runs inbound agent (WebSocket)
  → SlurmLauncher polls job status
  → Build on SlurmAgent / SlurmComputer
  → Build completes → Slurm job cancelled
```

## Key Classes and Boundaries

| Class | Role |
| --- | --- |
| `SlurmCloud` | Jenkins `AbstractCloudImpl`; cloud provider, provisioning entry point |
| `SlurmJobTemplate` | Maps to Slurm REST `job_desc_msg`; partition, CPUs, memory, GPUs, time limit |
| `SlurmJobBuilder` | Template + agent metadata → `JobDescMsg`; generates JNLP launcher script |
| `SlurmLauncher` | Submits job, waits for agent connection; extends `JNLPLauncher` |
| `SlurmAgent` / `SlurmComputer` | Agent lifecycle; extends `AbstractCloudSlave` |
| `SlurmClient` | OpenAPI-generated client wrapper; JWT authentication |
| `SlurmClientProvider` | Caffeine-cached `SlurmClient` factory |
| `PyxisConfig` | Optional container options injected into job script |
| `SlurmFolderProperty` | Folder-based cloud access control |
| `SlurmQueueTaskDispatcher` | Enforces folder restrictions at queue time |

Pipeline integration lives under `src/main/java/io/jenkins/plugins/slurm/pipeline/`:
`SlurmDeclarativeAgent`, `SlurmJobTemplateStep`, `SlurmJobTemplateStepExecution`.

## Extension Points

- **`SlurmJobTemplateSource`** — custom template sources (default: cloud UI config)
- **`SlurmJobTemplateFilter`** — chain-of-responsibility filter before template selection
- **`InProvisioning`** / **`DefaultInProvisioning`** — provisioning lifecycle hooks

## Dependency and Boundary Rules

1. **Slurm REST boundary** — All cluster interaction goes through `SlurmClient` (generated OpenAPI
   types). Do not hand-roll HTTP calls outside the client layer.
2. **Template boundary** — UI/JCasC/pipeline JSON is normalized into `SlurmJobTemplate` and
   `JobDescMsg` in `SlurmJobBuilder`. Inner provisioning code should not parse raw JSON.
3. **Jenkins cloud contract** — `SlurmCloud` implements Jenkins cloud APIs (`provision`,
   `canProvision`, retention). Keep Slurm-specific logic in launcher/builder/client, not in Jelly.
4. **Generated code** — `target/generated-sources/` is build output; edit the OpenAPI spec and
   preprocessing script (`scripts/preprocess-openapi-spec.py`), not generated files.
5. **JUnit 5 only** — JUnit 4 imports are banned by the `ban-junit4-imports` enforcer rule.

## Configuration Surfaces

1. **Jenkins UI** — Manage Jenkins → Clouds → Slurm (`config.jelly`)
2. **JCasC** — YAML configuration-as-code
3. **Pipeline** — `slurm {}` declarative agent or `slurmJobTemplate()` step

See `docs/product/configuration.md` and `README.md` for field-level reference.

## Related Docs

- `CLAUDE.md` / `README.md` — build commands and class map
- `docs/design/TEMPLATE_ARCHITECTURE.md` — template system design
- `docs/JSON_CONFIGURATION.md` — inline JSON / REST field mapping
- `docs/UI_CONFIGURATION.md` — UI field reference
- `TODO.md` — open issues and known gaps vs Kubernetes plugin
