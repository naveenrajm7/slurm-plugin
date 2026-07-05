# Product Overview — Slurm Plugin for Jenkins

## Purpose

Run **dynamic Jenkins agents as Slurm batch jobs**. When a build needs an agent with a matching
label, the plugin submits a job to a Slurm cluster via the REST API (`slurmrestd`), starts an
inbound Jenkins agent inside that job, executes the build, then cancels the Slurm job.

## Users

- **Jenkins administrators** — configure Slurm clouds, credentials, and job templates.
- **Pipeline authors** — use labels, declarative `slurm {}` agents, or `slurmJobTemplate()` steps.
- **HPC / research platforms** — bridge Jenkins CI/CD with existing Slurm scheduling.

## Core Behaviors

1. **Cloud configuration** — REST URL, JWT credentials, default partition, max agents, timeouts.
2. **Job templates** — Map Jenkins labels to Slurm job parameters (partition, CPUs, memory, GPUs,
   time limit, instance cap, idle minutes).
3. **Agent provisioning** — Submit Slurm job → wait for inbound WebSocket agent → assign build.
4. **Teardown** — Cancel Slurm job when the agent is no longer needed (subject to retention/idle
   settings).
5. **Pipeline integration** — Declarative agent and pipeline step with inline JSON matching Slurm
   REST `job_desc_msg`.
6. **Optional containers** — Pyxis/Enroot options for agents inside `.sqsh` images.
7. **Folder restrictions** — Limit which folders may use which clouds.

## Prerequisites (deployment)

- Slurm **24.11+** with `slurmrestd` and JWT authentication.
- Jenkins controller (need not run inside the Slurm cluster).
- Compute nodes must reach `JENKINS_URL` over WebSocket for inbound agents.

## Non-Goals

- Running the Jenkins controller on Slurm (out of scope; controller is external).
- Replacing Slurm scheduling policy (plugin submits jobs; Slurm schedules them).
- Kubernetes-style pod YAML (uses Slurm REST `job_desc_msg` JSON instead).

## Canonical User Docs

- `README.md` — setup, usage, configuration reference, troubleshooting.
- `docs/QUICK_START.md` — getting started guide.
- `docs/JSON_CONFIGURATION.md` — JSON / REST field reference.
