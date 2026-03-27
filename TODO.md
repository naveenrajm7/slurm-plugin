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

## Done
