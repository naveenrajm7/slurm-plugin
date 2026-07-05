# Configuration Contract

How operators and pipeline authors configure the Slurm plugin.

## Surfaces

| Surface | Location | Use when |
| --- | --- | --- |
| Jenkins UI | Manage Jenkins → Clouds → Slurm | Interactive setup, job templates |
| JCasC | `jenkins.clouds[].slurm` | GitOps / immutable controller config |
| Declarative pipeline | `agent { slurm { ... } }` | Per-pipeline or per-stage agent JSON |
| Pipeline step | `slurmJobTemplate(cloud:, json:) { }` | Ephemeral template scoped to a block |

## Cloud Fields (summary)

| Field | Purpose |
| --- | --- |
| `name` | Cloud identifier |
| `slurmRestApiUrl` | Base URL for `slurmrestd` |
| `credentialsId` | Secret file JWT token |
| `defaultPartition` | Fallback partition |
| `maxAgents` | Concurrent agent cap for this cloud |
| `agentTimeoutMinutes` | Wait for agent connection |
| `jenkinsUrl` | URL agents use to connect (auto-detect if empty) |
| `jobTemplates` | Static label → Slurm parameter profiles |

## Template Fields (summary)

| Field | Slurm mapping |
| --- | --- |
| `label` | Jenkins node labels (space-separated) |
| `partition` | Slurm partition |
| `cpusPerTask` | `--cpus-per-task` |
| `memoryPerNode` | Memory in MB |
| `timeLimit` | Wall-clock minutes |
| `tresPerJob` | Generic resources (e.g. `gres/gpu:1`) |
| `instanceCap` | Max concurrent agents from this template |
| `idleMinutes` | Reuse idle agent before teardown |

## JSON Inline Format

Pipeline `json` accepts structures mapping to [Slurm REST `job_desc_msg`](https://slurm.schedmd.com/rest_api.html),
plus optional `pyxis` block for containers. See `docs/JSON_CONFIGURATION.md`.

## Validation Expectations

- Descriptor form validation is covered by unit tests (`SlurmCloudTest`, `SlurmJobTemplateTest`).
- **Test Connection** in the UI requires reachable `slurmrestd` (manual / integration).
- Invalid pipeline JSON throws at parse time (`SlurmJobTemplateStepTest`).
