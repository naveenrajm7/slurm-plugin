# SLURM Job Template UI Configuration

## Updated UI (config.jelly)

The UI has been updated to reflect the simplified SLURM job template structure that maps directly to SLURM's REST API.

## UI Sections

### 1. Template Identification
```
┌─────────────────────────────────────────────┐
│ Name: [default_________________]            │
│ Labels: [linux gpu____________]            │
│ Usage: [▼ EXCLUSIVE          ]             │
└─────────────────────────────────────────────┘
```

### 2. SLURM Job Configuration
**Core fields that map directly to v0.0.42_job_desc_msg:**

```
┌─────────────────────────────────────────────┐
│ SLURM Job Configuration                     │
├─────────────────────────────────────────────┤
│ Partition: [gpu_________________]           │
│ Working Directory: [/tmp/jenkins__]         │
│ CPUs per Task: [8___]                       │
│ Memory per Node (MB): [16384]               │
│ Time Limit (minutes): [120__]               │
└─────────────────────────────────────────────┘
```

### 3. GPU / TRES Configuration
**New section for GPU and trackable resource allocation:**

```
┌─────────────────────────────────────────────┐
│ GPU / TRES Configuration                    │
├─────────────────────────────────────────────┤
│ TRES per Job:                               │
│ [gres/gpu:gfx942:1_______________]          │
│                                             │
│ TRES per Node: [___________________]        │
│ TRES per Task: [___________________]        │
└─────────────────────────────────────────────┘
```

### 4. Additional SLURM Options
**Optional advanced fields:**

```
┌─────────────────────────────────────────────┐
│ Additional SLURM Options                    │
├─────────────────────────────────────────────┤
│ Minimum Nodes: [1___]                       │
│ Number of Tasks: [1___]                     │
│ Account: [research_____________]            │
│ Quality of Service (QOS): [high__]          │
│ Node Constraints: [AMD&MI250X____]          │
│ Environment Variables:                      │
│ ┌─────────────────────────────────────────┐ │
│ │                                         │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### 5. Custom Job Script
**For advanced users who want to provide custom batch script:**

```
┌─────────────────────────────────────────────┐
│ Custom Job Script                           │
├─────────────────────────────────────────────┤
│ Custom SLURM Batch Script:                  │
│ ┌─────────────────────────────────────────┐ │
│ │ #!/bin/bash                             │ │
│ │ # Custom script content...              │ │
│ │                                         │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### 6. Agent Management
**Jenkins-specific configuration:**

```
┌─────────────────────────────────────────────┐
│ Agent Management                            │
├─────────────────────────────────────────────┤
│ Instance Capacity: [5___]                   │
│ Idle Minutes: [10__]                        │
└─────────────────────────────────────────────┘
```

## Example Configuration: GPU Build Agent

### Basic Setup
```yaml
Name: gpu-ml-training
Labels: gpu ml cuda
Usage: EXCLUSIVE
```

### SLURM Job Configuration
```yaml
Partition: gpu
Working Directory: /scratch/jenkins
CPUs per Task: 16
Memory per Node (MB): 65536  # 64GB
Time Limit (minutes): 480     # 8 hours
```

### GPU Configuration
```yaml
TRES per Job: gres/gpu:gfx942:4  # 4 AMD MI250X GPUs
TRES per Node: (empty)
TRES per Task: (empty)
```

### Additional Options
```yaml
Minimum Nodes: 1
Number of Tasks: 1
Account: ai-research
QOS: high
Node Constraints: AMD&MI250X
Environment Variables: (empty)
```

### Agent Management
```yaml
Instance Capacity: 2   # Max 2 concurrent agents
Idle Minutes: 15       # Keep alive 15 min after idle
```

## Field Descriptions

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| **Name** | Text | "default" | Unique template identifier |
| **Labels** | Text | "" | Space-separated Jenkins labels |
| **Usage** | Enum | EXCLUSIVE | EXCLUSIVE or NORMAL mode |
| **Partition** | Text | "" | SLURM partition name |
| **Working Directory** | Text | "/tmp/jenkins" | Job working directory |
| **CPUs per Task** | Number | 1 | CPUs allocated per task |
| **Memory per Node (MB)** | Number | 1024 | Memory in megabytes |
| **Time Limit (minutes)** | Number | 60 | Maximum runtime |
| **TRES per Job** | Text | "" | GPU/TRES allocation (e.g., "gres/gpu:type:count") |
| **TRES per Node** | Text | "" | TRES per node (optional) |
| **TRES per Task** | Text | "" | TRES per task (optional) |
| **Minimum Nodes** | Number | 1 | Minimum node count |
| **Number of Tasks** | Number | 1 | Task count (agent = 1 task) |
| **Account** | Text | "" | SLURM account for billing |
| **QOS** | Text | "" | Quality of Service |
| **Node Constraints** | Text | "" | Required node features |
| **Environment Variables** | Textarea | "" | JSON array of env vars |
| **Custom Script** | Textarea | "" | Custom batch script |
| **Instance Capacity** | Number | 1 | Max concurrent agents |
| **Idle Minutes** | Number | 5 | Idle timeout |

## Validation Rules

### Required Fields
- Name (must be unique)

### Numeric Validations
- CPUs per Task: min = 1
- Memory per Node: min = 512 MB
- Time Limit: min = 10 minutes
- Instance Capacity: min = 1
- Minimum Nodes: min = 1
- Number of Tasks: min = 1
- Idle Minutes: min = 0

### Format Validations
- **TRES per Job**: Should match pattern `type/subtype:name:count`
  - Example: `gres/gpu:gfx942:1`
  - Warning if format doesn't match

### Warnings
- Memory < 512 MB: "Might be insufficient for Jenkins agent"
- Time Limit < 10 min: "Might be too short"
- Minimum Nodes > 1: "Jenkins agents typically use single node"
- Number of Tasks > 1: "Jenkins agents typically run as single task"

## Changes from Previous Version

### Removed Fields
- ❌ `nodes` - Replaced with `minimumNodes` (more precise)
- ❌ `ntasks` - Replaced with `tasks` (clearer naming)
- ❌ `memory` (string) - Replaced with `memoryPerNode` (Long in MB)
- ❌ `timeLimit` (string) - Replaced with `timeLimit` (Integer in minutes)
- ❌ `workingDirectory` - Replaced with `currentWorkingDirectory`
- ❌ `additionalSbatchArgs` - Replaced with specific TRES fields
- ❌ `jobScript` - Renamed to `script` (matches SLURM API)
- ❌ `instanceCapStr` - Renamed to `instanceCap` (clearer naming)

### Added Fields
- ✅ `tresPerJob` - GPU/TRES allocation (e.g., "gres/gpu:gfx942:1")
- ✅ `tresPerNode` - TRES per node
- ✅ `tresPerTask` - TRES per task
- ✅ `account` - SLURM account
- ✅ `qos` - Quality of Service
- ✅ `constraints` - Node constraints/features
- ✅ `environment` - Environment variables

### Field Type Changes
- `memory`: String → `memoryPerNode`: Long (MB)
- `timeLimit`: String → `timeLimit`: Integer (minutes)
- `instanceCapStr`: int → `instanceCap`: int

## Benefits of New UI

1. **Direct API Mapping**: Fields match SLURM REST API exactly
2. **GPU Support**: Dedicated section for GPU/TRES configuration
3. **Clearer Units**: Memory in MB, time in minutes (no parsing needed)
4. **Better Organization**: Logical grouping of related fields
5. **Helpful Descriptions**: Each field explains expected format
6. **Validation**: Real-time validation with helpful error messages
7. **Future-Proof**: Easy to add more SLURM fields as needed

## Next Steps

1. ✅ UI updated (config.jelly)
2. ✅ Plugin built (slurm.hpi)
3. ⏭️ Test UI in Jenkins
4. ⏭️ Implement job submission with new template structure
5. ⏭️ Implement SlurmLauncher for agent connection

## Installation & Testing

1. Upload `target/slurm.hpi` to Jenkins
2. Navigate to: **Manage Jenkins → Configure Clouds → Add Cloud → SLURM**
3. Configure cloud connection (URL, credentials)
4. Click "Add Template" to see the new UI
5. Configure a template with GPU settings
6. Test connection and template validation
