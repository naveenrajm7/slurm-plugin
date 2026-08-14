# CI Workload Migration Plan — Native Slurm Agents (multi-agent per node)

## Context

This plan supersedes an earlier container-migration approach (Docker → Enroot/Pyxis with SquashFS
images) for the way we are **actually** shipping the Slurm plugin.

**What changed:** the plugin now launches the Jenkins inbound agent **natively** on the Slurm
compute node (see [`NATIVE_AGENT_SETUP.md`](NATIVE_AGENT_SETUP.md)). The agent JVM runs directly
on the host; the existing CI Jenkinsfiles keep doing their own `withDockerContainer { ... }`
inside that agent. We are **not**:

- migrating Docker → Enroot/Pyxis,
- converting images to SquashFS,
- splitting out a separate image-build pipeline,
- stripping `withDockerContainer` out of the Jenkinsfiles.

So most line-by-line edits in that older plan (SquashFS templates, image-build pipeline) are
**out of scope / will not be done**.

**What this plan is instead:** with native launch, the plugin (and the current hand-rolled
stopgap) packs **multiple agents onto a single physical node** — e.g. 8 agents, one GPU each, on
an 8-GPU node. The old static setup was **one machine = one build**; several CI workloads
implicitly rely on that and **collide** when 2+ builds share a host. This document catalogs those
collisions and how to accommodate them.

> Validated live on a multi-GPU node (Slurm 24.11) during the stopgap rollout, using inbound TCP
> JNLP4 because WebSocket/HTTPS was not reachable in that environment.

---

## The core principle

> Anything a build binds, locks, or writes at a **host-global** scope (a fixed localhost port, a
> shared filesystem path, a single lockfile) will collide when N agents run on one node. Each of
> these needs to become **per-agent** (unique port / path / lock) or be routed to a shared,
> concurrency-safe backend.

---

## Collisions found (and status)

### 1. Remoting `-workDir` lock — RESOLVED by native launch design

Remoting locks `<workDir>/remoting`. Two agents sharing a `-workDir` → the second fails with
"agent already running". The plugin/stopgap gives each agent a **unique** workDir
(`/var/jenkins/agents/<agent-name>`), so this is handled. Keep `$HOME=/var/jenkins` so home/ccache
paths still resolve.

- **Action:** none for the plugin (already per-agent). Ensure any docs/scripts keep workDir unique.

### 2. Build workspace cross-node collision — RESOLVED by per-agent remoteFS

Jenkins de-duplicates workspaces (`job@2`) only **per Computer**, not across nodes that secretly
share a filesystem. With unique per-agent `remoteFS` (matching the unique workDir), each agent's
`WORKSPACE` is distinct, so this is handled.

- **Action:** none, as long as each Jenkins node's Remote root directory = its unique workDir.

### 3. sccache local server + stunnel port collision — OPEN (CK) ⚠️ **primary blocker**

**Symptom:** second concurrent CK build on the node dies with
`sccache: error: Server startup failed: Address in use`.

**Root cause:** CK build containers use **host networking**, so all agents share the host's
`127.0.0.1`, and CK starts fixed-port localhost services:

- CK containers: `--network=host`
  (`projects/composablekernel/Jenkinsfile`, `get_docker_options()`).
- sccache daemon on default `127.0.0.1:4226`
  (`projects/composablekernel/script/sccache_wrapper.sh` → `sccache --start-server`).
- stunnel on `127.0.0.1:6379`
  (`projects/composablekernel/script/redis-cli.conf` → `accept = 127.0.0.1:6379`).

The old VMs each had their own network namespace, so `4226`/`6379` never clashed. With N native
agents sharing one host + `--network=host`, agent #2 can't bind them.

**MIOpen is NOT affected:** it uses `ccache` (a compiler-launcher, no daemon/port) with a Redis
secondary store, not an sccache server.

**Fixes:**

- **Stopgap / immediate:** run CK with **`USE_SCCACHE=false`** (build parameter). Skips the whole
  sccache+stunnel block; builds still compile & test (uncached). Good enough to validate that
  workloads *run* under multi-agent.
- **Proper (CK repo change; pick one):**
  - assign a **unique `SCCACHE_SERVER_PORT`** and a **unique stunnel `accept` port** per agent
    (derive from a per-agent index), **or**
  - **drop `--network=host`** for the build container so each gets its own loopback namespace
    (verify GPU/`/dev/kfd` access still works without host net).
- **Plugin consideration:** expose a stable **per-agent index / unique env** (e.g. an
  `SCCACHE_SERVER_PORT` computed from the agent slot) that workloads can consume — this makes the
  "unique local port" fix trivial for any daemon-style tool, not just sccache.

### 4. Git ref-repo mirror race — OPEN (CK + MIOpen) ⚠️

`checkout` uses a shared mirror at `/var/jenkins/ref-repo/rocm-libraries`, guarded by a
**per-`NODE_NAME`** lock (`lock("git ref repo lock - ${env.NODE_NAME}")`) in both
`composablekernel/Jenkinsfile` and `miopen/Jenkinsfile`. With N agents = N distinct node names on
one shared filesystem, the lock does **not** serialize them → concurrent `git remote update` /
mirror clone on the same directory can race/corrupt.

Old VMs: each had its own `/var/jenkins/ref-repo`, so the per-node lock sufficed.

**Fixes (workload repo):**
- use a **host-wide lock label** (not per-`NODE_NAME`) so all agents on a node serialize, **or**
- give each agent a **per-agent ref-repo path**, **or**
- use Jenkins global `lock()` keyed on the physical host.

### 5. Memory not scheduler-enforced — OPEN (depends on cluster policy)

If the cluster's select plugin is CPU-only (`SelectTypeParameters=CR_CPU` rather than
`CR_CPU_Memory`), **`--mem` is a no-op**: `AllocMem=0`, `mem` is absent from `AllocTRES` even when
the job requests it, and there is no reservation or cgroup RAM cap. N builds then share node RAM
freely, and a runaway build can OOM-kill its neighbors — including an agent JVM, which surfaces in
Jenkins as "Connection was broken". Verify with `scontrol show job <id>` before assuming `--mem`
gives you isolation.

- **CPU** *is* enforced/split correctly: `--cpus-per-task=<total/N>` yields exactly a 1/N slice,
  confirmed via `AllocTRES cpu=…`.
- **GPU** exclusivity works: `--gres=gpu:<model>:1` per agent; the Slurm cgroup scopes visible
  devices so each agent (and its builds) sees exactly one GPU. GPU count is also the effective cap
  on how many agents fit on a node.

**Fixes:**
- **Admin/cluster:** enable `SelectTypeParameters=CR_CPU_Memory` + cgroup `ConstrainRAMSpace=yes`
  for real per-agent RAM isolation (out of the plugin's control).
- **Meanwhile:** rely on total RAM headroom (node RAM ÷ agents per node) and/or cap build
  parallelism per agent (`-j` / `CTEST_PARALLEL_LEVEL`). Check `dmesg -T | grep -i oom` when an
  agent disconnects.

---

## Template guidance for the plugin (per-agent GPU node)

For a 256-CPU / 8-GPU node, one-GPU-per-agent slicing:

- `cpus_per_task`: `32` (1/8; use `30` to leave OS headroom)
- `gres`: `gpu:1` (or model-qualified, e.g. `gpu:<model>:1`) — this is the real cap to 8 agents
- `memory_per_node`: set for intent, but **not enforced** unless the cluster tracks memory
- unique `current_working_directory` / `remoteFS` per agent (workDir lock, workspace separation)
- keep `$HOME` at the shared `/var/jenkins` so ccache/sccache-Redis + ref-repo paths resolve

---

## Summary: what to do vs. skip

| Item | Status |
|---|---|
| Per-agent workDir + remoteFS | Done (native launch) |
| GPU exclusivity via gres | Done (works) |
| CPU 1/8 split | Done (enforced) |
| sccache/stunnel host-port collision (CK) | **Open** — `USE_SCCACHE=false` now; unique ports / no host-net later |
| Git ref-repo per-node-lock race (CK+MIOpen) | **Open** — host-wide lock or per-agent mirror |
| Memory isolation | **Open** — needs cluster `CR_CPU_Memory` + cgroup |
| Docker→Enroot/SquashFS, image-build split, remove `withDockerContainer` | **Skipped** — native launch keeps containers in the Jenkinsfile |
