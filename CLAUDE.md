# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Jenkins plugin that runs dynamic Jenkins agents as Slurm jobs. Analogous to the Kubernetes plugin — when a build needs an agent, the plugin submits a Slurm job via the Slurm REST API (`slurmrestd`), the job starts a Jenkins inbound agent, builds run, then the job is cancelled.

**Key prerequisite**: Slurm cluster v24.11+ with `slurmrestd` enabled and JWT authentication.

## Build Commands

```bash
# Build the plugin (produces target/slurm.hpi)
mvn clean package

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=SlurmCloudTest

# Run tests matching a pattern
mvn test -Dtest=*Template*

# Start Jenkins locally with the plugin loaded (for manual testing)
mvn hpi:run

# Check code formatting
mvn spotless:check

# Fix code formatting
mvn spotless:apply

# Full build including integration tests
mvn verify
```

**Note**: The build auto-generates a Slurm REST API Java client from `src/main/resources/openapi/slurm-v0.0.42.json` via the `openapi-generator-maven-plugin`. Generated code lands in `target/generated-sources/`. A Python script (`scripts/preprocess-openapi-spec.py`) strips version prefixes from the spec before generation.

**Enforce JUnit 5**: JUnit 4 imports are banned by `ban-junit4-imports` plugin — always use JUnit 5 (`org.junit.jupiter`).

## Architecture

### Agent Provisioning Flow

```
Build needs agent (label "gpu")
  → SlurmCloud.provision(label)
  → SlurmJobTemplateUtils.getTemplateByLabel()    # finds matching SlurmJobTemplate
  → SlurmJobBuilder.build()                        # converts template → v0.0.42_job_desc_msg
  → SlurmClient.submitJob()                        # POST to slurmrestd
  → Slurm launches job on compute node
  → Job runs JNLP agent (connects back via WebSocket)
  → SlurmLauncher polls job status (5s intervals, 5min timeout)
  → Build executes on SlurmAgent/SlurmComputer
  → Build completes → Slurm job cancelled
```

### Key Classes

| Class | Role |
|-------|------|
| `SlurmCloud` | Jenkins `AbstractCloudImpl`; main cloud provider, handles provisioning |
| `SlurmJobTemplate` | Maps to Slurm REST `v0.0.42_job_desc_msg`; holds partition, CPUs, memory, GPUs, time limit |
| `SlurmJobBuilder` | Converts template + agent metadata → `JobDescMsg`; generates JNLP launcher script |
| `SlurmLauncher` | Submits job to Slurm, waits for agent connection; extends `JNLPLauncher` |
| `SlurmAgent` / `SlurmComputer` | Agent lifecycle; extends `AbstractCloudSlave` |
| `SlurmClient` | Wraps OpenAPI-generated client; authenticates via JWT (`X-Slurm-USER-TOKEN`) |
| `SlurmClientProvider` | Caffeine-cached factory for `SlurmClient` instances |
| `PyxisConfig` | Optional Pyxis/Enroot container configuration; injects container options into job script |
| `SlurmFolderProperty` | Folder-based access control (restricts clouds per folder) |
| `SlurmQueueTaskDispatcher` | Enforces folder-level cloud restrictions at queue time |

### Extension Points

- **`SlurmJobTemplateSource`** — provide templates from custom sources (default: cloud UI config)
- **`SlurmJobTemplateFilter`** — chain-of-responsibility filter before template selection
- **`InProvisioning`** / **`DefaultInProvisioning`** — hook into agent provisioning lifecycle

### Pipeline Integration

```groovy
// Declarative agent (handled by SlurmDeclarativeAgent)
agent {
    slurm {
        cloud 'my-cluster'
        json '{"job": {"partition": "gpu", "tres_per_job": "gres:gpu:1"}}'
    }
}

// Pipeline step (handled by SlurmJobTemplateStep)
slurmJobTemplate(cloud: 'my-cluster', json: '...') {
    // steps
}
```

### Configuration Sources

1. **Jenkins UI**: Manage Jenkins → Clouds → Add Cloud → Slurm
2. **JCasC**: YAML-based configuration-as-code (tested via `configuration-as-code` test-scope dependency)
3. **Pipeline**: Inline JSON in `slurm {}` declarative agent or `slurmJobTemplate()` step

### UI (Jelly Templates)

Jelly templates and help HTML live in `src/main/resources/io/jenkins/plugins/slurm/`. Each class with a configuration form has a matching `config.jelly`. I18n strings are in `Messages.properties`.

## Reference Codebases

| Alias | Path | When to use |
|-------|------|-------------|
| k8s / kubernetes plugin | `../kubernetes` | Bug fixes, feature inspiration, patterns — read source there when asked to "refer to k8s" |

## TODO Tracking

A `TODO.md` file at the repo root tracks issues and items to revisit. Always read `TODO.md` at the start of a conversation. When the user says "add a TODO" or reports an issue to revisit later, append it to the `## Open` section. When an item is resolved, move it to `## Done`.

## Source Layout

```
src/main/java/io/jenkins/plugins/slurm/
  ├── client/          # SlurmClient, SlurmClientProvider
  └── pipeline/        # SlurmDeclarativeAgent, SlurmJobTemplateStep, SlurmJobTemplateStepExecution
src/main/resources/
  ├── openapi/         # slurm-v0.0.42.json (source for code generation)
  └── io/jenkins/plugins/slurm/  # Jelly UI + help HTML
src/test/java/io/jenkins/plugins/slurm/
  └── (5 test classes — Cloud, Template, PyxisConfig, DeclarativeAgent, TemplateStep)
docs/                  # Architecture docs, quick start, config guides
examples/              # Groovy pipeline examples
```
