# Test Matrix

Maps product behavior to proof for the Slurm Jenkins plugin. Proof command: `mvn -ntp test`.

Lint/format check: `mvn -ntp spotless:check` (see AGENTS.md — pre-existing trailing-whitespace
violations on a clean checkout; only run `spotless:apply` on files you change).

## Status Values

| Status | Meaning |
| --- | --- |
| planned | Accepted behavior, not implemented or not yet proven |
| in_progress | Actively being built |
| implemented | Implemented and proof exists |
| changed | Contract changed after earlier implementation |
| retired | No longer part of the product contract |

## Matrix

| Story / Area | Contract | Unit | Integration | E2E | Platform | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Cloud defaults & validation | `SlurmCloud` defaults, descriptor validation, max agents | yes | no | no | no | implemented | `SlurmCloudTest` |
| Cloud provisioning (`doCreate`) | Planned nodes, retention, label matching | yes | no | no | no | implemented | `SlurmCloudTest$DoCreateTest` |
| Retention strategy | Idle vs run-once retention selection | yes | no | no | no | implemented | `SlurmCloudTest$RetentionStrategySelectionTest` |
| Job template model | Defaults, setters, caps, idle minutes, descriptor | yes | no | no | no | implemented | `SlurmJobTemplateTest` |
| Pyxis container config | Container fields, null handling, serialization | yes | no | no | no | implemented | `PyxisConfigTest` |
| Declarative `slurm` agent | JSON/file config, SCM context, resources | yes | no | no | no | implemented | `SlurmDeclarativeAgentTest` |
| `slurmJobTemplate` step | JSON parsing, overrides, inherit, validation errors | yes | no | no | no | implemented | `SlurmJobTemplateStepTest` |
| Slurm REST job submission | Real `slurmrestd` submit/cancel lifecycle | no | no | no | yes | planned | Requires live Slurm cluster |
| Multi-cloud folder dispatch | Correct cloud/agent across restricted folders | no | no | no | no | planned | See `TODO.md` |
| Error messages in build console (static template) | Failures visible in build log, not only agent log | no | no | no | no | planned | See `TODO.md` |
| Graceful cloud deletion | Agent termination when cloud removed | no | no | no | no | planned | See `TODO.md` |

## Evidence Rules

- **Unit proof** — Jenkins rule tests (`JenkinsRule`) and pure config/model tests. Run:
  `mvn -ntp test` or `mvn -ntp test -Dtest=SlurmCloudTest`.
- **Integration proof** — Would require mock or live `slurmrestd`; not present in CI today.
- **E2E proof** — Manual: `mvn -ntp hpi:run`, configure cloud, run pipeline on Slurm agent.
- **Platform proof** — Slurm cluster + `jenkins/inbound-agent` container image on compute nodes.

Update proof status with `scripts/bin/harness-cli story add` / `story update` when using the
Harness CLI backlog; this matrix is the human-readable control panel.
