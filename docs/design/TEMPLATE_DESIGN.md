# SLURM Job Template Design

## Overview
The `SlurmJobTemplate` class has been simplified to map directly to SLURM's REST API `v0.0.42_job_desc_msg` structure. This design minimizes processing and allows future code-based template definitions to match the API structure exactly.

## Design Philosophy

1. **Direct API Mapping**: Fields map directly to SLURM's job submission API
2. **Minimal Processing**: Jenkins passes user-defined values directly to SLURM
3. **User Control**: Users make decisions about resource allocation, we just pass them through
4. **Future-Proof**: Code-based templates can be defined in the same structure as UI templates

## Template Structure

### Metadata Fields (Jenkins-specific)
```java
String id;                    // UUID for template identification
String name;                  // Template name (e.g., "gpu-build", "cpu-test")
String label;                 // Jenkins label (e.g., "linux", "gpu", "highmem")
Node.Mode nodeUsageMode;      // EXCLUSIVE or NORMAL
int instanceCap;              // Max concurrent agents from this template
int idleMinutes;              // Minutes before idle agent is terminated
```

### Core SLURM Fields (maps to v0.0.42_job_desc_msg)
```java
String partition;             // partition: SLURM partition (e.g., "gpu", "compute")
String currentWorkingDirectory;  // current_working_directory: job working directory
Integer cpusPerTask;          // cpus_per_task: CPUs per task (Jenkins agent runs as 1 task)
Long memoryPerNode;           // memory_per_node: memory in MB
String script;                // script: batch script (contains Jenkins agent launcher)
Integer timeLimit;            // time_limit: max runtime in minutes
```

### TRES Fields (for GPUs and other trackable resources)
```java
String tresPerJob;            // tres_per_job: e.g., "gres/gpu:gfx942:1"
String tresPerNode;           // tres_per_node: TRES allocation per node
String tresPerTask;           // tres_per_task: TRES allocation per task
```

### Additional Optional Fields
```java
Integer minimumNodes;         // minimum_nodes: default 1 (single node for agent)
Integer tasks;                // tasks: default 1 (agent runs as single task)
String account;               // account: SLURM account to charge
String qos;                   // qos: Quality of Service
String constraints;           // constraints: required node features
String environment;           // environment: environment variables (JSON array string)
```

## GPU Configuration Example

For requesting GPUs (like `srun --gres=gpu:gfx942:1`):

```java
SlurmJobTemplate gpuTemplate = new SlurmJobTemplate();
gpuTemplate.setName("gpu-build");
gpuTemplate.setLabel("gpu gfx942");
gpuTemplate.setPartition("gpu");
gpuTemplate.setCpusPerTask(8);
gpuTemplate.setMemoryPerNode(16384L);  // 16GB in MB
gpuTemplate.setTresPerJob("gres/gpu:gfx942:1");  // 1 GPU of type gfx942
gpuTemplate.setTimeLimit(120);  // 2 hours
```

## Default Values

- **minimumNodes**: 1 (Jenkins agents typically run on single nodes)
- **tasks**: 1 (Jenkins agent runs as single task)
- **cpusPerTask**: 1
- **memoryPerNode**: 1024 (1GB in MB)
- **timeLimit**: 60 (minutes)
- **currentWorkingDirectory**: "/tmp/jenkins"

## Job Submission Flow

When creating a SLURM job from this template:

1. Jenkins fills in the agent name → `name` field
2. Template fields map directly to OpenAPI model:
   ```java
   V0042JobDescMsg jobDesc = new V0042JobDescMsg();
   jobDesc.setName(agentName);
   jobDesc.setPartition(template.getPartition());
   jobDesc.setCurrentWorkingDirectory(template.getCurrentWorkingDirectory());
   jobDesc.setCpusPerTask(template.getCpusPerTask());
   jobDesc.setMemoryPerNode(template.getMemoryPerNode());
   jobDesc.setScript(template.getScript());
   jobDesc.setTimeLimit(template.getTimeLimit());
   jobDesc.setTresPerJob(template.getTresPerJob());
   jobDesc.setMinimumNodes(template.getMinimumNodes());
   jobDesc.setTasks(template.getTasks());
   // ... etc
   ```
3. Submit via REST API: `POST /slurm/v0.0.42/job/submit`

## UI Configuration

Users configure templates via Jenkins UI:

```
SLURM Job Template Configuration:
├── Name: "gpu-build"
├── Label: "gpu gfx942"
├── Partition: "gpu"
├── Working Directory: "/scratch/jenkins"
├── CPUs per Task: 8
├── Memory per Node (MB): 16384
├── Time Limit (minutes): 120
├── TRES per Job: "gres/gpu:gfx942:1"
├── Account: "research"
├── QOS: "high"
└── Constraints: "AMD&MI250X"
```

## Future: Code-Based Templates

Templates can be defined in code matching the same structure:

```groovy
// Jenkinsfile
pipeline {
    agent {
        slurm {
            template {
                name = "gpu-inference"
                label = "gpu inference"
                partition = "gpu"
                cpusPerTask = 16
                memoryPerNode = 32768
                tresPerJob = "gres/gpu:gfx942:4"
                timeLimit = 180
                account = "ai-research"
                qos = "high"
            }
        }
    }
    stages {
        // ...
    }
}
```

## Benefits of This Design

1. **Simplicity**: Direct mapping reduces complexity
2. **Flexibility**: Users have full control over SLURM parameters
3. **Maintainability**: Easy to add new SLURM fields as they become available
4. **Consistency**: Same structure for UI and code-based templates
5. **No Translation Layer**: Minimal processing between Jenkins and SLURM
6. **Future-Proof**: Can easily support new SLURM features by adding fields

## Next Steps

1. ✅ Template structure defined
2. ⏭️ Update UI forms (config.jelly) to match new fields
3. ⏭️ Implement job submission using OpenAPI client
4. ⏭️ Implement SlurmLauncher to connect Jenkins agent
5. ⏭️ Add template validation and testing

## Related Files

- `SlurmJobTemplate.java` - Template definition and validation
- `SlurmCloud.java` - Template selection and usage
- OpenAPI schema: `v0.0.42_job_desc_msg` in `slurm-v0.0.42.json`
