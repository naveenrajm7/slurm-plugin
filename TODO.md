# TODO

Issues and items to revisit, discovered during AI-assisted development and validation.

<!-- Usage: Ask Claude to "add a TODO" or edit this file directly. -->
<!-- Format: - [ ] Description (optional: context, file, severity) -->

## Open

- [ ] **Multi cloud instance testing** (ref: K8s plugin comparison)
  - [ ] **[HIGH]** Add multi-cloud test class — model after K8s `KubernetesQueueTaskDispatcherTest`: two clouds with folder restrictions, verify correct cloud/agent dispatch
  - [ ] **[HIGH]** Test folder property inheritance with multiple clouds — model after K8s `KubernetesFolderPropertyTest`
  - [ ] **[HIGH]** Test provisioning limits across multiple clouds — model after K8s `KubernetesProvisioningLimitsTest` (concurrent tasks across 3 clouds)
  - [ ] **[HIGH]** Test pipeline jobs with cloud restrictions — K8s has `checkPipelinesRestrictedTwoClouds()`
  - [ ] **[LOW]** Overlapping labels across clouds — neither K8s nor Slurm load-balances; no action unless we want to exceed K8s behavior
  - [ ] **[LOW]** Add help text to cloud field documenting "first available" fallback — K8s has this, we don't
- [ ] **Error messages not visible in build console for static-template provisioning** (`SlurmLauncher`)
  - When provisioned via a Jenkins label (no `slurmJobTemplate()` pipeline step), `jobTemplate.getListenerOrNull()` is null so `agent.runListener` is never set, and executor scanning finds nothing (agent never reached RUNNING). `getRunListener()` returns `TaskListener.NULL` → error messages fall back to the computer/agent log (Manage Jenkins → Nodes → agent → Log), not the build console.
  - Declarative agent path works correctly because `SlurmJobTemplateStepExecution` injects the build's `TaskListener` into the template before provisioning.
  - **Fix approach (model after K8s):** In `SlurmCloud.provision()` / `createPlannedNode()`, scan `Jenkins.get().getQueue()` for a `Queue.Item` whose label matches the template label and extract its `TaskListener` via `FlowExecutionOwner` (same pattern K8s uses in `KubernetesSlave.getRunListener()`). Inject that listener into the agent at creation time. Alternatively, store the `Queue.Item` ID on the agent and resolve its listener lazily at error time.
- [ ] **Graceful cloud deletion handling** (gap vs K8s plugin)
  - [ ] **[HIGH]** Add try/catch in SlurmAgent termination when cloud is missing — K8s logs warning + continues, we crash with IllegalStateException
  - [ ] **[MED]** Consider periodic cleanup for orphaned Slurm jobs — K8s has `GarbageCollection` class that cleans orphaned pods
- [ ] **Slurm job status visibility while provisioning** (discovered during label smoke testing — build shows "waiting/offline" while Slurm job is PD in queue)
  - [ ] **[HIGH]** Build console — log Slurm job ID, state, and pending reason (e.g. `PENDING (Priority)`) on each poll while queued; not only after agent is online
  - [ ] **[HIGH]** Wire `runListener` for cloud `node('label')` provisioning — attach queued build's `TaskListener` at agent creation (same queue scan as error-message TODO above); today only `slurmJobTemplate()` step sets it
  - [ ] **[MED]** Agent page — show live status via `SlurmComputer.getSlurmJobInfo()` + REST poll (e.g. `Slurm job 413835: PENDING (Priority)`)
  - [ ] **[MED]** Offline cause — set description to current Slurm state/reason instead of blank offline agent
  - [ ] **[MED]** External cancel — detect `CANCELLED` / missing job (404) immediately and surface "Slurm job was cancelled" in build console (fail fast, don't wait for agent timeout)
  - Context: `SlurmLauncher.waitForAgentConnection()` polls every 5s and logs generic "Waiting for Slurm job to start running…" to agent launch log only; pipeline `node('label')` users see no Slurm context in build console

## Done
