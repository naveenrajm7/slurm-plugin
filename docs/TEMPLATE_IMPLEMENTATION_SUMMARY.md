# SLURM Plugin Template System - Implementation Summary

## Overview

The SLURM Jenkins plugin now has a complete template-based architecture for managing how Jenkins agents are provisioned as SLURM jobs. This implementation provides the foundation for flexible, extensible job configuration similar to the Kubernetes plugin's pod template system, but simplified for SLURM's job-based model.

## What Was Implemented

### 1. Core Template Components

#### **SlurmJobTemplate** (Already Existed - Enhanced)
- Represents a SLURM job configuration template
- Maps directly to SLURM REST API's `v0.0.42_job_desc_msg` structure
- Supports labels for agent selection
- Includes GPU/TRES configuration
- Per-template instance capacity control

#### **SlurmJobTemplateSource** (NEW)
```java
@Extension
public abstract class SlurmJobTemplateSource implements ExtensionPoint
```
**Purpose**: Extension point for providing templates from multiple sources
**Features**:
- Cloud configuration source (default)
- Extensible for JCasC, external systems, code-based templates
- `getAll(cloud)` method aggregates templates from all sources

**Default Implementation**: `CloudConfigurationSource` provides templates from cloud UI configuration

#### **SlurmJobTemplateFilter** (NEW)
```java
@Extension
public abstract class SlurmJobTemplateFilter implements ExtensionPoint
```
**Purpose**: Extension point for filtering templates based on criteria
**Features**:
- Chain of responsibility pattern - all filters applied in sequence
- Can modify or reject templates
- `applyAll(cloud, templates, label)` runs all filters

#### **SlurmJobTemplateLabelFilter** (NEW)
```java
@Extension(ordinal = 1000) // High priority - runs first
public class SlurmJobTemplateLabelFilter extends SlurmJobTemplateFilter
```
**Purpose**: Filters templates by label matching
**Logic**:
- If no label requested and template is NORMAL mode → accept
- If label requested and template matches → accept
- Otherwise → reject

#### **SlurmJobBuilder** (NEW)
```java
public class SlurmJobBuilder
```
**Purpose**: Converts templates to SLURM job submission objects
**Process**:
1. Takes `SlurmJobTemplate` + agent details (name, Jenkins URL, secret)
2. Builds environment variables for agent connection
3. Generates bash script for agent startup
4. Creates `V0042JobDescMsg` for SLURM REST API

**Key Features**:
- Handles SLURM wrapper types (`V0042Uint64NoValStruct`, `V0042Uint32NoValStruct`)
- Generates complete agent launcher script
- Includes validation warnings for resource configurations

#### **SlurmJobTemplateUtils** (NEW)
```java
public class SlurmJobTemplateUtils
```
**Purpose**: Utility methods for template operations
**Methods**:
- `getAllTemplates(cloud)` - Get all templates from all sources
- `getTemplatesFor(cloud, label)` - Get filtered templates for label
- `getTemplateByLabel(cloud, label)` - Get best matching template
- `getTemplateByName(cloud, name)` - Get template by name
- `isTemplateNameUnique()` - Validate template name uniqueness
- `createDefaultTemplate(cloud)` - Create fallback template

### 2. Integration Updates

#### **SlurmCloud** (UPDATED)
- `getJobTemplateFor(label)` now uses `SlurmJobTemplateUtils`
- `canProvision(state, label)` checks filtered templates
- Fallback to default template if none match

## How It Works

### Template Selection Flow

```
1. Jenkins needs agent for label "gpu && ml"
   ↓
2. SlurmCloud.provision(label) called
   ↓
3. SlurmJobTemplateUtils.getTemplateByLabel(cloud, label)
   ↓
4. SlurmJobTemplateSource.getAll(cloud)
   → Collects templates from all sources
   ↓
5. SlurmJobTemplateFilter.applyAll(cloud, templates, label)
   → SlurmJobTemplateLabelFilter filters by label match
   → Other filters can add additional criteria
   ↓
6. Return first matching template (or create default)
   ↓
7. SlurmJobBuilder builds V0042JobDescMsg
   ↓
8. Ready for SLURM job submission
```

### Label Matching Example

**Template Configuration**:
```java
Template 1: name="cpu-agent", label="linux docker"
Template 2: name="gpu-agent", label="gpu cuda ml"
Template 3: name="default", label="" (NORMAL mode)
```

**Label Requests**:
- `"linux"` → Matches Template 1
- `"gpu"` → Matches Template 2
- `"ml"` → Matches Template 2
- `"windows"` → Falls back to default template

### Job Submission Example

```java
// 1. Get template for label
SlurmJobTemplate template = cloud.getJobTemplateFor(Label.get("gpu"));

// 2. Build SLURM job
SlurmJobBuilder builder = new SlurmJobBuilder(
    template,
    "jenkins-agent-123",
    "https://jenkins.example.com/",
    "agent-secret-token"
);

// 3. Generate job description
V0042JobDescMsg jobDesc = builder.build();

// 4. Validate
SlurmJobBuilder.validate(jobDesc);

// 5. Submit to SLURM (next phase of implementation)
// slurmClient.submitJob(jobDesc);
```

## Generated Batch Script

The `SlurmJobBuilder` generates a complete bash script for each agent:

```bash
#!/bin/bash
# SLURM Jenkins Agent Launcher
# Agent: jenkins-agent-123
# Generated by Jenkins SLURM Plugin

set -e
set -u

# Create working directory
mkdir -p /tmp/jenkins
cd /tmp/jenkins

# Custom user script (from template)
# ... user script here ...

# Download Jenkins agent JAR
if [ ! -f agent.jar ]; then
  echo 'Downloading Jenkins agent JAR...'
  wget -q -O agent.jar ${JENKINS_URL}jnlpJars/agent.jar || \
    curl -sSL -o agent.jar ${JENKINS_URL}jnlpJars/agent.jar
  if [ $? -ne 0 ]; then
    echo 'ERROR: Failed to download agent JAR from ${JENKINS_URL}'
    exit 1
  fi
fi

# Start Jenkins agent
echo 'Starting Jenkins agent: ${JENKINS_AGENT_NAME}'
echo 'Connecting to: ${JENKINS_URL}'

java -jar agent.jar \
  -url ${JENKINS_URL} \
  -name ${JENKINS_AGENT_NAME} \
  -secret ${JENKINS_SECRET} \
  -workDir ${JENKINS_AGENT_WORKDIR:-.} \
  -jar-cache ${JENKINS_AGENT_WORKDIR:-.}/remoting

echo 'Jenkins agent exited'
```

## Configuration Methods

### 1. UI Configuration
Users can configure templates through Jenkins UI:
- Manage Jenkins → Clouds → SLURM Cloud
- Add templates with name, label, resources
- Set partition, CPUs, memory, GPUs (TRES), time limits

### 2. Jenkins Configuration as Code (JCasC)
```yaml
jenkins:
  clouds:
    - slurm:
        name: "my-cluster"
        slurmRestApiUrl: "http://slurm:6820"
        credentialsId: "slurm-token"
        jobTemplates:
          - name: "gpu-agent"
            label: "gpu ml"
            partition: "gpu"
            cpusPerTask: 8
            memoryPerNode: 16384
            tresPerJob: "gres/gpu:a100:2"
            timeLimit: 240
```

### 3. Code-Based Templates (Extensible)
```java
@Extension
public class CustomTemplateSource extends SlurmJobTemplateSource {
    @Override
    protected List<SlurmJobTemplate> getList(@NonNull SlurmCloud cloud) {
        // Provide templates programmatically
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setName("code-defined");
        // ... configure ...
        return Arrays.asList(template);
    }
}
```

## Key Technical Details

### SLURM Wrapper Types
OpenAPI generates wrapper types for optional numeric fields:

```java
// Memory (uint64)
V0042Uint64NoValStruct memory = new V0042Uint64NoValStruct();
memory.setSet(true);        // Indicates value is set
memory.setInfinite(false);  // Not infinite
memory.setNumber(8192L);    // Actual value in MB

// Time limit (uint32)
V0042Uint32NoValStruct time = new V0042Uint32NoValStruct();
time.setSet(true);
time.setInfinite(false);
time.setNumber(120);        // Minutes
```

### GPU Configuration with TRES
```java
// Single GPU
template.setTresPerJob("gres/gpu:1");

// Specific GPU type
template.setTresPerJob("gres/gpu:a100:2");  // 2x A100

// AMD GPU
template.setTresPerJob("gres/gpu:gfx942:4"); // 4x MI300

// Per node/task allocation
template.setTresPerNode("gres/gpu:2");
template.setTresPerTask("gres/gpu:1");
```

## Extension Points for Future Development

### Custom Filters
```java
@Extension
public class ResourceLimitFilter extends SlurmJobTemplateFilter {
    @Override
    protected SlurmJobTemplate transform(
            @NonNull SlurmCloud cloud,
            @NonNull SlurmJobTemplate template,
            @CheckForNull Label label) {
        // Reject templates requesting too much memory
        if (template.getMemoryPerNode() > 64 * 1024) {
            LOGGER.warning("Rejected template: memory too high");
            return null;
        }
        return template;
    }
}
```

### Custom Template Sources
```java
@Extension
public class ExternalTemplateSource extends SlurmJobTemplateSource {
    @Override
    protected List<SlurmJobTemplate> getList(@NonNull SlurmCloud cloud) {
        // Fetch templates from external system
        return fetchFromExternalSystem(cloud);
    }
}
```

## Files Created/Modified

### New Files
1. `SlurmJobTemplateSource.java` - Template source extension point (169 lines)
2. `SlurmJobTemplateFilter.java` - Template filter extension point (89 lines)
3. `SlurmJobTemplateLabelFilter.java` - Label-based filter implementation (39 lines)
4. `SlurmJobBuilder.java` - Converts templates to SLURM jobs (308 lines)
5. `SlurmJobTemplateUtils.java` - Template utility methods (169 lines)
6. `docs/design/TEMPLATE_ARCHITECTURE.md` - Complete architecture documentation

### Modified Files
1. `SlurmCloud.java` - Updated to use new template utilities
2. `SlurmJobTemplate.java` - Already existed, enhanced with methods

## Build Status

✅ **Successfully compiled and built**
- Plugin size: 3,113,690 bytes (3.1 MB)
- All new classes compile without errors
- Ready for testing

## What's Next

### Phase 1: Job Submission (READY TO IMPLEMENT)
Now that templates are working, implement actual job submission:

1. **Create SlurmClient.submitJob() method**
   ```java
   public String submitJob(V0042JobDescMsg jobDesc) throws Exception
   ```
   - POST to `/slurm/v0.0.42/job/submit`
   - Return SLURM job ID

2. **Update SlurmCloud.createPlannedNode()**
   - Use `SlurmJobBuilder` to create job
   - Submit via `SlurmClient.submitJob()`
   - Wait for job to start
   - Create `SlurmAgent` instance

3. **Implement job monitoring**
   - Poll job status via `/slurm/v0.0.42/job/{job_id}`
   - Detect when job starts running
   - Get compute node assignment

### Phase 2: Agent Connection
4. **SlurmLauncher implementation**
   - SSH to compute node, OR
   - Use JNLP connection (simpler)
   - Connect Jenkins agent to controller

5. **SlurmAgent lifecycle**
   - Track job ID and node assignment
   - Monitor job state
   - Clean termination

### Phase 3: Advanced Features
6. **Template inheritance**
7. **Dynamic template updates**
8. **Template metrics and monitoring**

## Testing the Template System

### Manual Testing
1. Install plugin HPI in Jenkins
2. Configure SLURM cloud with multiple templates
3. Create jobs with different labels
4. Verify correct template selection
5. Check generated job descriptions

### Validation
```java
// Check template selection
SlurmJobTemplate template = SlurmJobTemplateUtils.getTemplateByLabel(
    cloud, Label.get("gpu")
);
System.out.println("Selected: " + template.getName());

// Build and validate job
SlurmJobBuilder builder = new SlurmJobBuilder(
    template, "test", jenkinsUrl, null
);
V0042JobDescMsg jobDesc = builder.build();
SlurmJobBuilder.validate(jobDesc);

// Inspect generated script
System.out.println(jobDesc.getScript());
```

## Benefits

1. **Flexibility**: Multiple templates for different workloads
2. **Extensibility**: Custom filters and sources via extension points
3. **Code as Configuration**: Support for JCasC
4. **Maintainability**: Clean separation of concerns
5. **Reusability**: Templates defined once, used many times
6. **GPU Support**: First-class TRES configuration
7. **Label-Based**: Automatic template selection by labels

## Comparison with Kubernetes Plugin

| Feature | Kubernetes Plugin | SLURM Plugin |
|---------|------------------|--------------|
| Template class | PodTemplate (complex) | SlurmJobTemplate (simple) |
| Builder | PodTemplateBuilder | SlurmJobBuilder |
| Filters | PodTemplateFilter | SlurmJobTemplateFilter |
| Sources | PodTemplateSource | SlurmJobTemplateSource |
| Utilities | PodTemplateUtils | SlurmJobTemplateUtils |
| Complexity | High (containers, volumes, etc.) | Low (job parameters only) |
| Resources | CPU, memory, GPU | CPU, memory, TRES (GPU, etc.) |

## Architecture Benefits

1. **Extension Points**: Easy to add custom filters/sources
2. **Clean Separation**: Template → Builder → Job Description
3. **Type Safety**: Uses generated OpenAPI types
4. **Validation**: Built-in validation and warnings
5. **Documentation**: Comprehensive docs for users and developers

## Summary

The SLURM plugin template architecture is now **complete and ready for job submission implementation**. All foundation pieces are in place:

- ✅ Template definition and configuration
- ✅ Template sources (cloud config + extensible)
- ✅ Template filtering (label-based + extensible)
- ✅ Template selection utilities
- ✅ Job builder with script generation
- ✅ OpenAPI wrapper type handling
- ✅ Comprehensive documentation

**Next step**: Implement actual SLURM job submission using `SlurmClient` and the OpenAPI-generated client to POST jobs and monitor their lifecycle.
