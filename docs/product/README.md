# Product Docs

Living product contract for the **Slurm Jenkins plugin**. Derived from `README.md` and
implementation; update here when behavior changes.

## Current Product Contracts

| File | Scope |
| --- | --- |
| `overview.md` | Purpose, users, core behaviors, prerequisites |
| `provisioning.md` | Agent provisioning lifecycle and known gaps |
| `configuration.md` | UI, JCasC, pipeline configuration surfaces |

## Update Rule

When behavior changes:

1. Update the affected product doc in this directory.
2. Update or create a story packet under `docs/stories/`.
3. Update proof in `docs/TEST_MATRIX.md` and/or via `scripts/bin/harness-cli story update`.
4. Record a decision in `docs/decisions/` if architecture, scope, or risk changes.
