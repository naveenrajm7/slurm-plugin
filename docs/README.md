# Documentation Map

This directory holds the project harness and any product contract derived from a
future user-provided spec.

## Main Files

- `HARNESS.md`: how humans and agents collaborate.
- `FEATURE_INTAKE.md`: how prompts become tiny, normal, or high-risk work.
- `ARCHITECTURE.md`: architecture discovery and boundary rules.
- `TEST_MATRIX.md`: legacy proof map; current proof status is queried with
  `scripts/bin/harness-cli query matrix`.
- `HARNESS_BACKLOG.md`: legacy improvement list; current improvement records
  are stored with `scripts/bin/harness-cli backlog`.
- `GLOSSARY.md`: shared terms.
- `SYMPHONY_QUICKSTART.md`: beginner-facing instructions for running Harness
  stories through Symphony.
- `SYMPHONY_SCOPE.md`: detailed scope for the Harness-native agent workbench
  and orchestration layer.

## Folders

- `product/`: current product truth, empty until a spec is derived.
- `stories/`: feature packets and backlog.
- `decisions/`: durable decisions and tradeoffs.
- `demo/`: concrete walkthroughs that show how the harness transforms input
  into agent-ready work.
- `templates/`: reusable spec-intake, story, plan, decision, and validation
  formats.

## Plugin-Specific Docs (pre-harness)

These existed before Harness and remain the detailed reference for Slurm plugin behavior:

- `QUICK_START.md`, `JSON_CONFIGURATION.md`, `UI_CONFIGURATION.md`
- `design/TEMPLATE_ARCHITECTURE.md`, `design/TEMPLATE_DESIGN.md`
- `SLURM_VERSION_UPGRADE_GUIDE.md`, `IDLE_MINUTES_BEHAVIOR.md`

## Current State

This repository is an **implemented Jenkins plugin** with unit tests and Maven CI. Harness was
added to give agents a stable operating model on top of the existing codebase. Product contracts
live in `docs/product/`; proof mapping in `docs/TEST_MATRIX.md`; open gaps in `TODO.md`.
