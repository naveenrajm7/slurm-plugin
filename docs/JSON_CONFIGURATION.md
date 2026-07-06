# JSON Configuration Format

The SLURM plugin uses JSON configuration that **matches the SLURM REST API format** exactly. This means:
- ✅ If it works in SLURM REST API, it works in the plugin
- ✅ Copy-paste configurations from working REST API calls
- ✅ Reference official SLURM REST API documentation

## Structure

```json
{
  "job": {
    // SLURM REST API job_desc_msg fields
  },
  "pyxis": {
    // Plugin-specific Pyxis/container configuration
  }
}
```

### Required Keys

- **`job`**: SLURM job description (matches `job_desc_msg` from REST API)
- **`pyxis`**: (Optional) Pyxis container configuration (plugin-specific)

## Complete Example

```json
{
  "job": {
    "partition": "gpu",
    "account": "myaccount",
    "cpus_per_task": 16,
    "memory_per_node": {
      "set": true,
      "number": 32768
    },
    "time_limit": {
      "set": true,
      "number": 120
    },
    "tres_per_job": "gres/gpu:gfx1030:1",
    "required_nodes": ["node1", "node2"],
    "excluded_nodes": ["node3"],
    "constraints": "avx2",
    "current_working_directory": "/tmp/jenkins",
    "tasks": 1,
    "minimum_nodes": 1
  },
  "pyxis": {
    "containerImage": "/home/user/jenkins-agent.sqsh",
    "containerMountHome": true,
    "containerWritable": false,
    "containerRemap": true
  }
}
```

## Job Fields (REST API)

All fields match SLURM REST API `job_desc_msg`. Common fields:

### Basic Resources
- **`partition`** (string): SLURM partition name
- **`account`** (string): Account to charge
- **`qos`** (string): Quality of Service
- **`cpus_per_task`** (integer): CPUs per task
- **`tasks`** (integer): Number of tasks
- **`minimum_nodes`** (integer): Minimum node count

### Memory
Can use simple number (MB) or REST API struct:

```json
// Simple format (MB)
"memory_per_node": 32768

// REST API format
"memory_per_node": {
  "set": true,
  "number": 32768
}
```

### Time Limit
Can use simple number (minutes) or REST API struct:

```json
// Simple format (minutes)
"time_limit": 120

// REST API format
"time_limit": {
  "set": true,
  "number": 120
}
```

### TRES (Trackable Resources)
- **`tres_per_job`** (string): TRES for entire job (e.g., `"gres/gpu:gfx1030:1"`)
- **`tres_per_node`** (string): TRES per node
- **`tres_per_task`** (string): TRES per task

### Node Selection
- **`required_nodes`** (array): Specific nodes required
  ```json
  "required_nodes": ["node1", "node2", "node[5-10]"]
  ```
- **`excluded_nodes`** (array): Nodes to exclude
  ```json
  "excluded_nodes": ["node3", "node4"]
  ```
- **`constraints`** (string): Required features (comma-separated)
  ```json
  "constraints": "avx2,gpu"
  ```
- **`prefer`** (string): Preferred but not required features

### Node Ranges
- **`nodes`** (string): Node count range (e.g., `"1-15:4"`)
- **`minimum_nodes`** (integer): Minimum node count
- **`maximum_nodes`** (integer): Maximum node count

### Working Directory & I/O
- **`current_working_directory`** (string): Job working directory
- **`standard_output`** (string): Path to stdout file
- **`standard_error`** (string): Path to stderr file

### Advanced
- **`reservation`** (string): Reservation name
- **`tasks_per_node`** (integer): Tasks per node
- **`ntasks_per_tres`** (integer): Tasks per TRES

## Pyxis Fields (Plugin-Specific)

Container configuration using Pyxis/Enroot:

- **`containerImage`** (string): Path to container image (`.sqsh` file)
- **`containerMountHome`** (boolean): Mount home directory
- **`containerMounts`** (string): Additional mounts (e.g., `"/data:/data"`)
- **`containerWorkdir`** (string): Container working directory
- **`containerWritable`** (boolean): Make container writable
- **`containerRemap`** (boolean): Remap root user
- **`containerName`** (string): Container name

## Usage in Pipelines

### Declarative Agent

```groovy
pipeline {
    agent {
        slurm {
            cloud 'my-cluster'
            json '''
            {
              "job": {
                "partition": "gpu",
                "cpus_per_task": 16,
                "memory_per_node": {"set": true, "number": 32768},
                "required_nodes": ["controller-node"]
              },
              "pyxis": {
                "containerImage": "/home/user/jenkins-agent.sqsh",
                "containerMountHome": true
              }
            }
            '''
        }
    }
    stages {
        stage('Test') {
            steps {
                sh 'nvidia-smi'
            }
        }
    }
}
```

### Scripted Pipeline (slurmJobTemplate step)

```groovy
slurmJobTemplate(
    cloud: 'my-cluster',
    json: '''
    {
      "job": {
        "partition": "gpu",
        "cpus_per_task": 8,
        "tres_per_job": "gres/gpu:1"
      },
      "pyxis": {
        "containerImage": "/path/to/image.sqsh"
      }
    }
    '''
) {
    node(POD_LABEL) {
        sh 'nvidia-smi'
    }
}
```

## Comparison with SLURM REST API

### REST API Job Submission

```bash
curl -X POST "http://slurm-api/slurm/v0.0.43/job/submit" \
  -H "Content-Type: application/json" \
  -d '{
    "job": {
      "partition": "gpu",
      "cpus_per_task": 16,
      "required_nodes": ["node1"]
    }
  }'
```

### Plugin Declarative Agent (Same Structure!)

```groovy
agent {
    slurm {
        cloud 'my-cluster'
        json '''
        {
          "job": {
            "partition": "gpu",
            "cpus_per_task": 16,
            "required_nodes": ["node1"]
          }
        }
        '''
    }
}
```

The `job` section is **identical** between REST API and plugin!

## Migration from Simple Format

**Old format (NO LONGER SUPPORTED):**
```json
{
  "partition": "gpu",
  "cpus": 16,
  "memory": "32G"
}
```

**New REST API format:**
```json
{
  "job": {
    "partition": "gpu",
    "cpus_per_task": 16,
    "memory_per_node": {"set": true, "number": 32768}
  }
}
```

## Field Name Reference

| Simple Name | REST API Name | Type |
|------------|---------------|------|
| `cpus` | `cpus_per_task` | integer |
| `memory` | `memory_per_node` | object/integer |
| `time` | `time_limit` | object/integer |
| `workingDir` | `current_working_directory` | string |
| `gres` | `tres_per_job` | string |
| `tasksPerNode` | `tasks_per_node` | integer |
| `ntasksPerTres` | `ntasks_per_tres` | integer |
| `standardOutput` | `standard_output` | string |
| `standardError` | `standard_error` | string |

## Reference Documentation

- [SLURM REST API Documentation](https://slurm.schedmd.com/rest_api.html)
- [SLURM sbatch Options](https://slurm.schedmd.com/sbatch.html)
- [Pyxis Documentation](https://github.com/NVIDIA/pyxis)
