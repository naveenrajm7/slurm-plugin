# 0008 — Adopt repository-harness

## Status

Accepted

## Context

Coding agents working on this Jenkins plugin need stable, repo-local answers to: what to read
first, how risky a change is, what proof is required, and which decisions to inherit. Chat history
and `CLAUDE.md` alone do not encode feature intake, validation expectations, or durable decisions.

## Decision

Install [repository-harness](https://github.com/hoangnb24/repository-harness) in **merge** mode:

- Preserve existing `AGENTS.md` (Cursor Cloud instructions) and append a Harness shim block.
- Add harness docs under `docs/` alongside existing plugin docs (`QUICK_START.md`, design/, etc.).
- Add `scripts/bin/harness-cli` via installer (gitignored binary; re-run installer to refresh).
- Customize `docs/ARCHITECTURE.md`, `docs/TEST_MATRIX.md`, and `docs/product/` for this plugin.

## Consequences

- Agents should read `docs/HARNESS.md` and `docs/FEATURE_INTAKE.md` before non-trivial work.
- Product truth lives in `docs/product/`; implementation gaps tracked in `TODO.md` and test matrix.
- Harness generic decision records (`0001`–`0007`) remain as upstream harness history; plugin-specific
  decisions start at `0008`.
