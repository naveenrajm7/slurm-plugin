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
  - **Partial fix:** `JobUtils.findRunListenerForLabel()` + queue scan wired in `SlurmCloud.createPlannedNode()` (same PR as job status visibility). Error messages during provisioning should now reach the build console for `node('label')`; verify edge cases remain.
- [ ] **Graceful cloud deletion handling** (gap vs K8s plugin)
  - [ ] **[HIGH]** Add try/catch in SlurmAgent termination when cloud is missing — K8s logs warning + continues, we crash with IllegalStateException
  - [ ] **[MED]** Consider periodic cleanup for orphaned Slurm jobs — K8s has `GarbageCollection` class that cleans orphaned pods
- [ ] **Slurm compute node visibility** (discovered during CK workload validation — agent page shows Jenkins synthetic name, not where the job landed)
  - [ ] **[HIGH]** Ensure `SlurmAgent.nodeList` is populated when job reaches RUNNING — `updateProvisioningStatus()` sets it when `JobInfo.nodes` is returned; verify on live cluster and backfill if nodes arrive only at RUNNING
  - [ ] **[HIGH]** Agent UI — richer sidepanel on `SlurmComputer` (partition + compute hostname(s)); basic `SlurmJobStatusAction` box exists in job-status PR
  - [ ] **[MED]** Build console — when agent connects, log `Slurm job <id> on node(s) <host>` (not only the Jenkins synthetic agent name)
  - [ ] **[MED]** Agent description — update from "Slurm agent from template …" to include partition + nodelist once known
  - [ ] **[LOW]** Optional env injection — expose `SLURM_JOB_ID` / `SLURM_NODELIST` on the Jenkins agent node for pipeline `echo $SLURM_NODELIST` / debugging
  - **Workaround today:** open the agent **Log** (launch log lists Slurm job ID when RUNNING); on the login node run `squeue -u $USER` or `scontrol show job <id>`; inside the running pipeline use `sh 'hostname'` (build is already on the compute node, but Jenkins UI does not show that hostname)

## Done

- [x] **Slurm job status visibility while provisioning** (`feature/slurm-job-status-visibility`)
  - Build console logs `[Slurm] Slurm job <id>: <state> (<reason>)` on each state change during `waitForAgentConnection()`
  - `JobUtils.findRunListenerForLabel()` wires build `TaskListener` for static-template `node('label')` provisioning
  - Agent page: `SlurmJobStatusAction` box + REST poll of `slurmJobInfo`
  - Fail-fast on `CANCELLED` / missing job (404)
  - Note: offline cause during launch uses live status in agent UI only (`setTemporaryOfflineCause` blocks scheduling)
