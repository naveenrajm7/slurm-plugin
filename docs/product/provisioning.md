# Provisioning Contract

## Trigger

A Jenkins build requests an agent with a label (freestyle `node('label')`, pipeline `agent { label
'gpu' }`, or declarative `slurm {}` / `slurmJobTemplate()`).

## Selection

1. `SlurmCloud.provision(Label)` is invoked for each configured Slurm cloud.
2. `SlurmJobTemplateUtils.getTemplateByLabel()` finds a matching `SlurmJobTemplate`.
3. Optional `SlurmJobTemplateFilter` chain may adjust or reject templates.
4. Folder restrictions (`SlurmFolderProperty`, `SlurmQueueTaskDispatcher`) may block queueing.

## Job Creation

1. `SlurmJobBuilder.build()` converts template + agent metadata to `v0.0.42_job_desc_msg`.
2. Launcher script injects `JENKINS_URL`, `JENKINS_SECRET`, `JENKINS_AGENT_NAME`.
3. `SlurmClient.submitJob()` POSTs to `slurmrestd`.

## Agent Connection

1. `SlurmLauncher` (extends `JNLPLauncher`) polls job status (default 5s interval).
2. Compute node runs inbound agent over **WebSocket**.
3. Timeout controlled by `agentTimeoutMinutes` (cloud default or system property).

## Teardown

- Build completes → retention strategy decides whether to keep the agent for reuse (`idleMinutes`)
  or terminate immediately (`runOnce`).
- Slurm job is cancelled when the agent is torn down (unless `keepJobOnFailure`).

## Known Gaps

See `TODO.md`:

- Static-template provisioning may not attach build `TaskListener` (errors only in agent log).
- Cloud deletion during active agents may throw instead of degrading gracefully.
- Multi-cloud folder dispatch lacks dedicated test coverage.
