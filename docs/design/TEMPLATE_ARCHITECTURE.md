# SLURM Job Template Architecture

## Overview

The SLURM plugin implements a template-based system for defining how Jenkins agents are provisioned as SLURM jobs. This architecture is inspired by the Kubernetes plugin but simplified for SLURM's job-based model.

## Core Components

### 1. SlurmJobTemplate

**Purpose**: Defines the configuration for SLURM jobs that will run Jenkins agents.

**Key Features**:
- Direct mapping to SLURM REST API's `v0.0.42_job_desc_msg` structure
- Label-based agent selection
- Instance capacity control per template
- Support for GPUs via TRES (Trackable RESources)

**Usage**:
```java
SlurmJobTemplate template = new SlurmJobTemplate();
template.setName("gpu-agent");
template.setLabel("gpu && ml");
template.setPartition("gpu");
template.setCpusPerTask(4);
template.setMemoryPerNode(8192L); // 8GB in MB
template.setTresPerJob("gres/gpu:a100:1"); // 1 A100 GPU
template.setTimeLimit(180); // 3 hours
template.setInstanceCap(5); // Max 5 concurrent agents
```

### 2. SlurmJobTemplateSource

**Purpose**: Extension point for providing templates from different sources.

**Sources**:
- **Cloud Configuration**: Templates defined in Jenkins cloud configuration UI
- **Code-based Configuration**: Templates defined in JCasC (Jenkins Configuration as Code)
- **External Systems**: Future support for dynamic templates from external sources

**Extension Point**:
```java
@Extension
public class CustomTemplateSource extends SlurmJobTemplateSource {
    @Override
    protected List<SlurmJobTemplate> getList(@NonNull SlurmCloud cloud) {
        // Return templates from your custom source
        return myCustomTemplates;
    }
}
```

### 3. SlurmJobTemplateFilter

**Purpose**: Extension point for filtering templates based on criteria.

**Built-in Filters**:
- **Label Filter** (`SlurmJobTemplateLabelFilter`): Filters templates by label matching
- **Custom Filters**: Extensible for permission checks, resource limits, etc.

**Extension Point**:
```java
@Extension
public class ResourceLimitFilter extends SlurmJobTemplateFilter {
    @Override
    protected SlurmJobTemplate transform(
            @NonNull SlurmCloud cloud,
            @NonNull SlurmJobTemplate template,
            @CheckForNull Label label) {
        // Return null to reject template, or modified template
        if (template.getMemoryPerNode() > 32768) {
            return null; // Reject templates requesting >32GB
        }
        return template;
    }
}
```

### 4. SlurmJobBuilder

**Purpose**: Converts a template into actual SLURM job submission objects.

**Process**:
1. Takes a `SlurmJobTemplate` and agent details
2. Generates environment variables for Jenkins agent connection
3. Creates bash script for agent startup
4. Builds `V0042JobDescMsg` for SLURM REST API submission

**Example**:
```java
SlurmJobBuilder builder = new SlurmJobBuilder(
    template,
    "my-agent-123",
    "https://jenkins.example.com/",
    "secret-token"
);

V0042JobDescMsg jobDesc = builder.build();
// Submit jobDesc to SLURM via REST API
```

### 5. SlurmJobTemplateUtils

**Purpose**: Utility methods for template operations.

**Key Methods**:
- `getAllTemplates(cloud)` - Get all templates from all sources
- `getTemplatesFor(cloud, label)` - Get filtered templates for a label
- `getTemplateByLabel(cloud, label)` - Get best matching template
- `getTemplateByName(cloud, name)` - Get template by name
- `createDefaultTemplate(cloud)` - Create fallback default template

## Template Selection Flow

```
1. Jenkins needs agent for label "gpu && linux"
   ↓
2. SlurmCloud.provision(label) called
   ↓
3. SlurmJobTemplateUtils.getTemplateByLabel(cloud, label)
   ↓
4. SlurmJobTemplateSource.getAll(cloud) - Get all templates
   ↓
5. SlurmJobTemplateFilter.applyAll(cloud, templates, label) - Filter
   ↓
6. Return first matching template (or create default)
   ↓
7. SlurmJobBuilder.build() - Convert to SLURM job
   ↓
8. Submit job to SLURM REST API
   ↓
9. Monitor job and create SlurmAgent when running
```

## Label Matching

Templates use space-separated labels that are matched against requested labels:

**Template Configuration**:
```
Template 1: label = "linux docker"
Template 2: label = "gpu cuda"
Template 3: label = "" (empty = matches anything in NORMAL mode)
```

**Label Requests**:
- Request: `"linux"` → Matches Template 1
- Request: `"gpu"` → Matches Template 2
- Request: `"linux && docker"` → Matches Template 1
- Request: `"windows"` → Falls back to default template

## Configuration Methods

### 1. UI Configuration

Navigate to **Manage Jenkins → Clouds → Add SLURM Cloud** and configure templates in the UI:

- Set cloud name, URL, credentials
- Add job templates with labels and resources
- Set instance caps and timeouts

### 2. Jenkins Configuration as Code (JCasC)

```yaml
jenkins:
  clouds:
    - slurm:
        name: "my-slurm-cluster"
        slurmRestApiUrl: "http://slurm-controller:6820"
        credentialsId: "slurm-jwt-token"
        defaultPartition: "compute"
        maxAgents: 20
        agentTimeoutMinutes: 60
        jobTemplates:
          - name: "default-agent"
            label: "linux"
            partition: "compute"
            cpusPerTask: 2
            memoryPerNode: 4096
            timeLimit: 120
            instanceCap: 10
            
          - name: "gpu-agent"
            label: "gpu cuda"
            partition: "gpu"
            cpusPerTask: 8
            memoryPerNode: 16384
            tresPerJob: "gres/gpu:a100:1"
            timeLimit: 240
            instanceCap: 5
```

### 3. Code-based Templates (Future)

Templates can be defined programmatically and loaded via `SlurmJobTemplateSource`:

```java
@Extension
public class MyTemplateSource extends SlurmJobTemplateSource {
    @Override
    protected List<SlurmJobTemplate> getList(@NonNull SlurmCloud cloud) {
        List<SlurmJobTemplate> templates = new ArrayList<>();
        
        // Define template in code
        SlurmJobTemplate gpuTemplate = new SlurmJobTemplate();
        gpuTemplate.setName("code-defined-gpu");
        gpuTemplate.setLabel("gpu ml");
        gpuTemplate.setPartition("gpu");
        gpuTemplate.setCpusPerTask(16);
        gpuTemplate.setMemoryPerNode(32768L);
        gpuTemplate.setTresPerJob("gres/gpu:a100:2");
        
        templates.add(gpuTemplate);
        return templates;
    }
}
```

## GPU Configuration with TRES

SLURM uses TRES (Trackable RESources) for GPUs and other specialized resources:

**Common TRES Formats**:
```
# Single GPU of any type
tresPerJob: "gres/gpu:1"

# Specific GPU type
tresPerJob: "gres/gpu:a100:1"
tresPerJob: "gres/gpu:v100:2"
tresPerJob: "gres/gpu:gfx942:4"

# GPU per node
tresPerNode: "gres/gpu:2"

# GPU per task
tresPerTask: "gres/gpu:1"
```

## Instance Capacity Control

Templates support per-template instance caps to limit concurrent agents:

```java
template.setInstanceCap(5); // Max 5 agents from this template
```

**Hierarchy**:
1. Cloud-level max agents (overall limit)
2. Template-level instance cap (per-template limit)

Both limits are enforced during provisioning.

## Node Usage Mode

Templates support two usage modes:

**EXCLUSIVE** (default):
- Only accepts jobs with matching labels
- Template: `label = "gpu"` only accepts label requests containing "gpu"

**NORMAL**:
- Accepts any job if label is empty
- Used for "catch-all" templates

```java
template.setNodeUsageMode(Node.Mode.EXCLUSIVE); // Only labeled jobs
template.setNodeUsageMode(Node.Mode.NORMAL);    // Any job if label empty
```

## Benefits Over Direct Job Submission

1. **Reusability**: Define templates once, use for many agents
2. **Flexibility**: Different templates for different workloads
3. **Extensibility**: Custom filters and sources via extension points
4. **Code as Configuration**: Support for JCasC and programmatic templates
5. **Label Matching**: Automatic template selection based on job labels
6. **Resource Control**: Per-template instance caps and resource limits

## Migration from Simple Job Submission

**Before** (hypothetical simple approach):
```java
// Hardcoded job parameters
V0042JobDescMsg job = new V0042JobDescMsg();
job.setName("jenkins-agent");
job.setPartition("compute");
job.setCpusPerTask(2);
// ... many more fields
```

**After** (template-based):
```java
// Select template, all parameters configured in template
SlurmJobTemplate template = cloud.getJobTemplateFor(label);
SlurmJobBuilder builder = new SlurmJobBuilder(template, agentName, jenkinsUrl, secret);
V0042JobDescMsg job = builder.build();
```

## Future Enhancements

1. **Template Inheritance**: Templates can inherit from parent templates
2. **Dynamic Templates**: Templates fetched from external systems
3. **Template Validation**: Pre-submission validation via extension points
4. **Template Metrics**: Track usage, success rates per template
5. **Template Scheduling**: Time-based template selection
6. **Template Priorities**: Priority-based template selection when multiple match

## Testing Templates

Test template configuration before provisioning agents:

```java
// Validate template builds properly
SlurmJobTemplate template = cloud.getJobTemplateFor(Label.get("gpu"));
SlurmJobBuilder builder = new SlurmJobBuilder(template, "test", jenkinsUrl, null);
V0042JobDescMsg jobDesc = builder.build();

// Check generated script
String script = jobDesc.getScript();
System.out.println("Generated script:\n" + script);

// Validate job description
SlurmJobBuilder.validate(jobDesc);
```

## Debugging

Enable debug logging to see template selection:

```
java.util.logging.ConsoleHandler.level = FINE
io.jenkins.plugins.slurm.level = FINE
```

Logs will show:
- Templates loaded from each source
- Filtering decisions for each template
- Selected template for each provision request
- Generated job description details
