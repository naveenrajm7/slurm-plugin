# SLURM Plugin - Quick Start Guide

## Template System Overview

The SLURM plugin uses a template-based system to define how Jenkins agents are provisioned as SLURM jobs.

## Basic Concepts

### Templates
- **SlurmJobTemplate**: Defines SLURM job parameters (CPUs, memory, partition, GPUs, etc.)
- Each template has a **name** and **label** for selection
- Multiple templates can be configured per cloud

### Label Matching
- Jenkins jobs request agents with labels (e.g., "gpu && linux")
- Plugin selects template with matching label
- Falls back to default template if no match

## Quick Configuration

### 1. Configure SLURM Cloud
```
Manage Jenkins → Clouds → Add SLURM Cloud
- Name: my-slurm
- URL: http://slurm-controller:6820
- Credentials: (JWT token)
- Max Agents: 20
```

### 2. Add Job Templates

**CPU Template**:
```
Name: cpu-agent
Label: linux
Partition: compute
CPUs per Task: 2
Memory per Node: 4096 (MB)
Time Limit: 120 (minutes)
Instance Cap: 10
```

**GPU Template**:
```
Name: gpu-agent
Label: gpu ml
Partition: gpu
CPUs per Task: 8
Memory per Node: 16384 (MB)
TRES per Job: gres/gpu:a100:1
Time Limit: 240 (minutes)
Instance Cap: 5
```

### 3. Use in Pipeline
```groovy
pipeline {
    agent {
        label 'gpu'  // Will use gpu-agent template
    }
    stages {
        stage('Train') {
            steps {
                sh 'python train.py'
            }
        }
    }
}
```

## Template Fields

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| name | String | Template identifier | "gpu-agent" |
| label | String | Space-separated labels | "gpu ml cuda" |
| partition | String | SLURM partition | "gpu" |
| cpusPerTask | Integer | CPUs per task | 8 |
| memoryPerNode | Long | Memory in MB | 16384 |
| timeLimit | Integer | Max time in minutes | 240 |
| tresPerJob | String | GPUs/resources | "gres/gpu:a100:2" |
| instanceCap | Integer | Max concurrent agents | 5 |
| script | String | Custom setup script | "module load cuda" |

## GPU Configuration (TRES)

### Common TRES Formats
```
# Any GPU
tresPerJob: "gres/gpu:1"

# NVIDIA A100
tresPerJob: "gres/gpu:a100:2"

# AMD MI300
tresPerJob: "gres/gpu:gfx942:4"

# Per-node allocation
tresPerNode: "gres/gpu:2"
```

## JCasC Configuration

```yaml
jenkins:
  clouds:
    - slurm:
        name: "my-slurm"
        slurmRestApiUrl: "http://slurm:6820"
        credentialsId: "slurm-jwt"
        defaultPartition: "compute"
        maxAgents: 20
        jobTemplates:
          - name: "default"
            label: ""
            partition: "compute"
            cpusPerTask: 2
            memoryPerNode: 4096
            timeLimit: 120
            instanceCap: 10
            
          - name: "gpu-large"
            label: "gpu large"
            partition: "gpu"
            cpusPerTask: 16
            memoryPerNode: 32768
            tresPerJob: "gres/gpu:a100:4"
            timeLimit: 480
            instanceCap: 2
```

## Architecture Components

```
User Job (label: "gpu")
        ↓
SlurmCloud.provision()
        ↓
SlurmJobTemplateUtils.getTemplateByLabel()
        ↓
[Sources] → [Filters] → [Select Best Match]
        ↓
SlurmJobBuilder.build()
        ↓
V0042JobDescMsg (SLURM job)
        ↓
Submit to SLURM (next phase)
```

## Extension Points

### Custom Template Source
```java
@Extension
public class MyTemplateSource extends SlurmJobTemplateSource {
    @Override
    protected List<SlurmJobTemplate> getList(@NonNull SlurmCloud cloud) {
        // Return templates from custom source
        return loadMyTemplates();
    }
}
```

### Custom Filter
```java
@Extension
public class MyFilter extends SlurmJobTemplateFilter {
    @Override
    protected SlurmJobTemplate transform(
            @NonNull SlurmCloud cloud,
            @NonNull SlurmJobTemplate template,
            @CheckForNull Label label) {
        // Modify or reject template
        if (shouldReject(template)) {
            return null;  // Reject
        }
        return template;  // Accept
    }
}
```

## Common Operations

### List All Templates
```java
List<SlurmJobTemplate> templates = 
    SlurmJobTemplateUtils.getAllTemplates(cloud);
```

### Find Template by Label
```java
SlurmJobTemplate template = 
    SlurmJobTemplateUtils.getTemplateByLabel(cloud, label);
```

### Build Job Description
```java
SlurmJobBuilder builder = new SlurmJobBuilder(
    template, agentName, jenkinsUrl, secret
);
V0042JobDescMsg jobDesc = builder.build();
```

### Validate Configuration
```java
SlurmJobBuilder.validate(jobDesc);  // Throws if invalid
```

## Troubleshooting

### No Template Found
- Check template labels match job labels
- Verify template nodeUsageMode (EXCLUSIVE vs NORMAL)
- Check cloud has templates configured

### Template Not Selected
- Enable debug logging: `io.jenkins.plugins.slurm.level = FINE`
- Check filter logs for rejection reasons
- Verify label matching logic

### Resource Limits
- Check instance cap not exceeded
- Verify cloud max agents not reached
- Check SLURM partition limits

## Next Steps

1. ✅ Templates configured
2. ⏳ Implement job submission
3. ⏳ Monitor job lifecycle
4. ⏳ Connect Jenkins agent
5. ⏳ Production testing

## Resources

- Template Architecture: `docs/design/TEMPLATE_ARCHITECTURE.md`
- Full Implementation: `docs/TEMPLATE_IMPLEMENTATION_SUMMARY.md`
- UI Configuration: `docs/UI_CONFIGURATION.md`
- Template Design: `docs/design/TEMPLATE_DESIGN.md`
