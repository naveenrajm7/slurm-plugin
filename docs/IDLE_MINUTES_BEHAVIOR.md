# Understanding `idleMinutes` Configuration

## Overview

The `idleMinutes` parameter controls how long a SLURM agent remains active after becoming idle (i.e., after completing a build and having no work assigned).

## Behavior by Configuration

| `idleMinutes` Value | Behavior | Use Case | Risks |
|---------------------|----------|----------|-------|
| **0** | Agent terminates **immediately** when idle | One-shot agents: terminate right after one build completes | ⚠️ Agent may terminate before build assignment if there's network latency or queue processing delay |
| **1** | Agent waits 1 minute idle before termination | Fast cleanup with safety buffer | ✅ Recommended minimum for run-once agents |
| **5** (default) | Agent waits 5 minutes idle before termination | Balance between reusability and resource efficiency | ✅ Good for most use cases |
| **10+** | Agent waits longer idle period | High reusability - multiple builds can use same agent | Higher SLURM resource usage |

## Important Notes

### ⚠️ Common Misconception
**INCORRECT:** "Setting `idleMinutes=0` means the agent is deleted immediately when it starts"  
**CORRECT:** "Setting `idleMinutes=0` means the agent terminates immediately **after completing a build** when it becomes idle"

### Agent Lifecycle

```
1. Provisioning → Agent requested from Jenkins
2. SLURM Job Submitted → Job submitted to SLURM queue
3. Job Running → SLURM allocates resources
4. Agent Connects → Agent connects to Jenkins controller
5. Build Assigned → Build work assigned to agent
6. Build Executes → Build runs on agent
7. Build Completes → Build finishes
8. Agent Idle → Agent has no more work
   └─ [IDLE TIMEOUT STARTS HERE]
   └─ After idleMinutes: Agent terminated
9. Cleanup → SLURM job cancelled (via scancel)
```

### `idleMinutes` Always Applies

The `idleMinutes` setting is **always used** by `CloudRetentionStrategy`, regardless of whether `runOnce` is enabled or disabled:

- **`runOnce=true` + `idleMinutes=0`**: One-shot mode - immediate termination after build
- **`runOnce=true` + `idleMinutes=5`**: Single build, but waits 5 minutes idle before cleanup
- **`runOnce=false` + `idleMinutes=5`**: Reusable agent - can handle multiple builds within 5-minute idle window
- **`runOnce=false` + `idleMinutes=0`**: Not recommended - agent terminates immediately, defeating reusability

## Recommended Configuration

### For One-Shot Agents (run once per build)
```
runOnce: true
idleMinutes: 1  ← Recommended minimum
```
**Rationale:** Provides a 1-minute safety buffer for build assignment while still cleaning up quickly.

### For Reusable Agents (multiple builds)
```
runOnce: false
idleMinutes: 5  ← Default value
```
**Rationale:** Allows agent to service multiple builds efficiently while not consuming resources indefinitely.

### For High-Traffic Labels
```
runOnce: false
idleMinutes: 10-15
```
**Rationale:** Keeps agents alive longer to handle frequent build requests, reducing SLURM job submission overhead.

## Risks of `idleMinutes=0`

Setting `idleMinutes=0` can cause **build assignment failures** in the following scenarios:

1. **Network Latency**: Slight delay between agent connection and build assignment
2. **Queue Processing**: Jenkins queue takes time to assign work to new agent
3. **Race Conditions**: Agent connects → becomes idle → terminates before build assigned

### Warning in UI

When you set `idleMinutes=0`, the Jenkins UI will display:

> ⚠️ **Warning:** Using idleMinutes=0 enables one-shot mode: agent will terminate immediately after completing a build. This may cause build assignment failures if there are network delays. Consider using at least 1 minute for more reliable agent provisioning.

## Interaction with `keepJobOnFailure`

The `keepJobOnFailure` flag is independent of `idleMinutes`:

- **`keepJobOnFailure=false`** (default): SLURM job is cancelled via `scancel` when agent terminates
- **`keepJobOnFailure=true`**: SLURM job is **NOT** cancelled - left running for debugging

This applies regardless of the `idleMinutes` setting.

## Code Implementation

The retention strategy is implemented in `SlurmCloud.java`:

```java
hudson.slaves.RetentionStrategy<?> retentionStrategy = 
    new hudson.slaves.CloudRetentionStrategy(jobTemplate.getIdleMinutes());
```

When `idleMinutes=0`, `CloudRetentionStrategy` immediately terminates the agent upon becoming idle (no grace period).

## Best Practices

1. **Never use `idleMinutes=0` in production** unless you have fast, reliable networks and understand the risks
2. **Use `idleMinutes=1` as minimum** for run-once agents
3. **Use `idleMinutes=5` (default)** for most reusable agents
4. **Monitor agent provisioning failures** - if you see "agent terminated before build assignment", increase `idleMinutes`
5. **Balance resource usage** - higher `idleMinutes` = more SLURM resources held, but better reusability

## Troubleshooting

### Problem: Builds failing with "Agent terminated before execution"
**Solution:** Increase `idleMinutes` to at least 1 minute

### Problem: Too many SLURM resources consumed
**Solution:** Decrease `idleMinutes` or enable `runOnce=true`

### Problem: Frequent SLURM job submission overhead
**Solution:** Increase `idleMinutes` to allow agent reuse (e.g., 10-15 minutes)

## Related Settings

- **`runOnce`**: Controls whether agent is intended for single build vs multiple builds
- **`keepJobOnFailure`**: Controls whether SLURM job is kept for debugging on failure
- **`instanceCap`**: Controls maximum number of agents from this template
- **SLURM `--time` limit**: Separate hard timeout for SLURM job itself

## References

- Jenkins `CloudRetentionStrategy` JavaDoc
- Kubernetes Plugin retention strategy patterns
- SLURM `scancel` documentation
