# Slurm plugin for Jenkins

Jenkins plugin to run dynamic agents in a Slurm cluster.

Similar to the [Kubernetes plugin](https://plugins.jenkins.io/kubernetes/),
automates the scaling of Jenkins agents running as Slurm jobs.

The plugin creates a Slurm job for each agent started, and cancels it after each build.

Agents are launched as inbound agents via WebSocket, so the compute node connects automatically to the Jenkins controller.
The following environment variables are automatically injected into each agent job:

* `JENKINS_URL` : Jenkins web interface URL
* `JENKINS_SECRET` : the secret key for authentication
* `JENKINS_AGENT_NAME` : the name of the Jenkins agent

Tested with a sqsh image of [`jenkins/inbound-agent`](https://hub.docker.com/r/jenkins/inbound-agent),
see the [Docker image source code](https://github.com/jenkinsci/docker-agent).

It is not required to run the Jenkins controller inside the Slurm cluster.

# 📜 Table of Contents

- [Generic setup](#generic-setup)
- [Usage](#usage)
- [Configuration reference](#configuration-reference)
- [Declarative pipeline](#declarative-pipeline)
- [Pyxis / Enroot container support](#pyxis--enroot-container-support)
- [Folder-based cloud restrictions](#folder-based-cloud-restrictions)
- [Features controlled using system properties](#features-controlled-using-system-properties)
- [Troubleshooting](#troubleshooting)
- [Building and testing](#building-and-testing)
- [Agent harness](#agent-harness)
- [Related projects](#related-projects)


# Generic Setup

## Prerequisites

* A running Slurm cluster **24.11 or later**
* `slurmrestd` enabled with JWT authentication ([Slurm REST quick start](https://slurm.schedmd.com/rest_quickstart.html#basic_usage))
* A Jenkins instance
* The Slurm plugin installed
* (Optional) Pyxis and Enroot for container support

## Configuration

Open the Jenkins UI and navigate to **Manage Jenkins → Clouds → Add a new cloud → Slurm**.
Enter the *Slurm REST URL* and *Jenkins URL* appropriately.

Supported credentials:

* **Secret File** — a file containing the JWT token

Agents connect over **WebSocket** (HTTP/S) by default. This works whether Jenkins runs
inside or outside the Slurm cluster, and simplifies setup when Jenkins is behind a reverse proxy.
See [JEP-222](https://jenkins.io/jep/222) for more.

Use the **Test Connection** button to verify communication from Jenkins to `slurmrestd`.

![image](images/cloud-configuration.png)

## Static job templates

In the **Job Templates** section you can pre-configure reusable agent profiles.
Each template maps a Jenkins label to a set of Slurm job parameters.

Key template fields:

* **Name** — identifier for the template
* **Label** — space-separated Jenkins node labels; jobs requesting any of these get this template
* **Partition** — Slurm partition (queue)
* **CPUs per task** — `--cpus-per-task`
* **Memory per node** — in MB
* **Time limit** — wall-clock minutes
* **TRES per job** — generic resources, e.g. `gres/gpu:a100:1`
* **Instance cap** — maximum concurrent agents from this template
* **Idle minutes** — keep agent alive for reuse between builds

### Restricting what jobs can use your configured cloud

Clouds can be restricted to specific folders.
In the cloud's advanced configuration, check **Restrict pipeline support to authorized folders**.
Then add the cloud to the folder's configuration via **Folder → Configure → Slurm Clouds**.

# Usage

## Overview

The Slurm plugin allocates Jenkins agents as Slurm batch jobs.

Within each job there is always one process running the Jenkins inbound agent,
launched via `srun -N1 -n1` to ensure a single agent connection per job.

The agent process runs either directly on the compute node or inside a Pyxis/Enroot container
if container support is configured.

When multi-node allocations are requested (`minimum_nodes > 1`), the Jenkins agent runs on the
first node only, while your pipeline commands can utilise the full allocation using `srun` or MPI launchers.

## Using a label

Job templates configured in the UI declare a label.
When a freestyle or pipeline job requests `node('some-label')`, the Slurm cloud allocates a new
job to serve the agent.

The global template definition is the easiest migration path for existing jobs that already use labels.
New pipelines should use the `slurm` declarative agent or `slurmJobTemplate` step instead.

## Using the pipeline step

The `slurmJobTemplate` step defines an ephemeral job template scoped to the pipeline block.
It is removed when the block exits.

```groovy
slurmJobTemplate(cloud: 'my-cluster', json: '''
{
  "job": {
    "partition": "gpu",
    "cpus_per_task": 8,
    "tres_per_job": "gres/gpu:1"
  }
}
''') {
    node(SLURM_LABEL) {
        sh 'nvidia-smi'
    }
}
```

Find more examples in the [examples](examples) directory.


# Configuration Reference

## Job template

Templates can be configured via the Jenkins UI, JCasC YAML, or inline pipeline JSON.

### UI / JCasC fields

| Field | Description | Example |
|-------|-------------|---------|
| `name` | Template identifier | `gpu-agent` |
| `label` | Space-separated Jenkins labels | `gpu ml cuda` |
| `partition` | Slurm partition | `gpu` |
| `cpusPerTask` | CPUs per task | `8` |
| `memoryPerNode` | Memory in MB | `16384` |
| `timeLimit` | Wall-clock minutes | `240` |
| `tresPerJob` | Generic resources (GPUs, etc.) | `gres/gpu:a100:1` |
| `instanceCap` | Max concurrent agents from this template | `5` |
| `idleMinutes` | Minutes to keep idle agent before terminating | `10` |
| `nodeUsageMode` | `NORMAL` or `EXCLUSIVE` | `EXCLUSIVE` |
| `defaultPartition` | Fallback partition when template omits one | `compute` |
| `agentTimeoutMinutes` | Minutes to wait for agent to come online | `5` |

### JSON / pipeline inline format

The `json` field accepts a structure that maps directly to the [Slurm REST API `job_desc_msg`](https://slurm.schedmd.com/rest_api.html):

```json
{
  "job": {
    "partition": "gpu",
    "account": "myaccount",
    "cpus_per_task": 16,
    "memory_per_node": { "set": true, "number": 32768 },
    "time_limit": { "set": true, "number": 120 },
    "tres_per_job": "gres/gpu:gfx942:4",
    "minimum_nodes": 1,
    "required_nodes": ["node1"],
    "constraints": "avx2"
  },
  "pyxis": {
    "containerImage": "/home/user/jenkins-agent.sqsh",
    "containerMountHome": true,
    "containerWritable": false
  }
}
```

Simple integer forms are also accepted for `memory_per_node` (MB) and `time_limit` (minutes).

See [docs/JSON_CONFIGURATION.md](docs/JSON_CONFIGURATION.md) for the full field reference.

## JCasC example

```yaml
jenkins:
  clouds:
    - slurm:
        name: "my-cluster"
        slurmRestApiUrl: "http://slurm-controller:6820"
        credentialsId: "slurm-jwt"
        defaultPartition: "compute"
        maxAgents: 20
        agentTimeoutMinutes: 5
        jobTemplates:
          - name: "cpu-default"
            label: "linux"
            partition: "compute"
            cpusPerTask: 4
            memoryPerNode: 8192
            timeLimit: 120
            instanceCap: 10

          - name: "gpu-large"
            label: "gpu ml"
            partition: "gpu"
            cpusPerTask: 16
            memoryPerNode: 32768
            tresPerJob: "gres/gpu:a100:4"
            timeLimit: 480
            instanceCap: 2
```

# Declarative Pipeline

Agents can be defined inline using the `slurm` declarative agent:

```groovy
pipeline {
  agent {
    slurm {
      cloud 'my-cluster'
      json '''
      {
        "job": {
          "partition": "gpu",
          "cpus_per_task": 8,
          "tres_per_job": "gres/gpu:1"
        }
      }
      '''
    }
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

Or reference a static template by label:

```groovy
pipeline {
  agent {
    label 'gpu'   // resolved by the configured gpu-large template
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

## Per-stage agents

```groovy
pipeline {
  agent none
  stages {
    stage('Compile') {
      agent {
        label 'linux'
      }
      steps { sh 'make' }
    }
    stage('GPU test') {
      agent {
        slurm {
          cloud 'my-cluster'
          json '{"job": {"partition": "gpu", "tres_per_job": "gres/gpu:1"}}'
        }
      }
      steps { sh 'pytest tests/gpu/' }
    }
  }
}
```


# Pyxis / Enroot Container Support

If your Slurm cluster runs [Pyxis](https://github.com/NVIDIA/pyxis), the plugin can run agents
inside an Enroot container image.

Add a `pyxis` block to the job JSON:

```json
{
  "job": {
    "partition": "gpu",
    "cpus_per_task": 8,
    "tres_per_job": "gres/gpu:1"
  },
  "pyxis": {
    "containerImage": "/path/to/jenkins-agent.sqsh",
    "containerMountHome": true,
    "containerWritable": false,
    "containerMounts": "/data:/data",
    "containerRemap": true
  }
}
```

### Pyxis fields

| Field | Type | Description |
|-------|------|-------------|
| `containerImage` | string | Path to `.sqsh` container image |
| `containerMountHome` | boolean | Mount the user's home directory |
| `containerMounts` | string | Additional bind mounts (`host:container`) |
| `containerWorkdir` | string | Working directory inside container |
| `containerWritable` | boolean | Make the container layer writable |
| `containerRemap` | boolean | Remap root user in container |
| `containerName` | string | Name for the container instance |


# Folder-based Cloud Restrictions

Individual clouds can be restricted so that only jobs in specific folders may use them.

1. In the cloud's **Advanced** settings, check **Restrict pipeline support to authorized folders**.
2. In the folder's **Configure** page, select the clouds that folder is permitted to use.

Jobs outside permitted folders are blocked at queue time by `SlurmQueueTaskDispatcher`.


# Features Controlled Using System Properties

See [Features controlled by system properties](https://www.jenkins.io/doc/book/managing/system-properties/) for how to set these in Jenkins.

| Property | Default | Description |
|----------|---------|-------------|
| `io.jenkins.plugins.slurm.SlurmCloud.agentTimeoutMinutes` | `5` | Minutes to wait for an agent to connect before failing |


# Troubleshooting

## Check whether the Slurm job was submitted

In the build console look for the submitted job ID, e.g. `Submitted Slurm job 12345`.
If missing, the plugin failed before submission — check the Jenkins system log.

## Inspect a running or failed Slurm job

```bash
squeue --job 12345
scontrol show job 12345
sacct -j 12345 --format=JobID,State,ExitCode,NodeList
```

## Agent never connects

* Check the agent log: **Manage Jenkins → Nodes → \<agent\> → Log**.
* Verify `slurmrestd` is reachable from Jenkins.
* Ensure the compute node can reach `JENKINS_URL` over WebSocket.
* Confirm the JWT token has not expired.
* Increase agent timeout via `agentTimeoutMinutes` if node startup is slow.

## Enable debug logging

Add a [Jenkins log recorder](https://www.jenkins.io/doc/book/system-administration/viewing-logs/) for
`io.jenkins.plugins.slurm` at `FINE` level to see template selection, job submission, and polling details.


# Building and Testing

## Build

```bash
mvn clean package          # produces target/slurm.hpi
mvn spotless:apply         # fix formatting
```

## Run tests

```bash
mvn test                             # all tests
mvn test -Dtest=SlurmCloudTest       # single class
mvn test -Dtest='*Template*'         # pattern match
```

## Run Jenkins locally with the plugin

```bash
mvn hpi:run
```

Then open `http://localhost:8080/jenkins` and configure a Slurm cloud pointing at a real or mock `slurmrestd`.

> **Note:** The build auto-generates a Slurm REST API Java client from
> `src/main/resources/openapi/slurm-v0.0.42.json` via the `openapi-generator-maven-plugin`.
> Generated code lands in `target/generated-sources/`.


# Agent Harness

This repository uses [repository-harness](https://github.com/hoangnb24/repository-harness) so
coding agents (Cursor, Claude Code, Codex, etc.) have stable project context before changing code.

| Doc | Purpose |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | Agent entry point (local notes + Harness links) |
| [`docs/HARNESS.md`](docs/HARNESS.md) | Human–agent collaboration model |
| [`docs/FEATURE_INTAKE.md`](docs/FEATURE_INTAKE.md) | Classify work as tiny, normal, or high-risk |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Plugin architecture and boundaries |
| [`docs/TEST_MATRIX.md`](docs/TEST_MATRIX.md) | Behavior-to-proof expectations |
| [`docs/product/`](docs/product/) | Product contract |

Install or refresh the harness CLI:

```bash
curl -fsSL "https://raw.githubusercontent.com/hoangnb24/repository-harness/main/scripts/install-harness.sh?$(date +%s)" | bash -s -- --merge --yes
```

# Related Projects

* [Kubernetes plugin](https://github.com/jenkinsci/kubernetes-plugin) — the inspiration for this plugin's architecture
* [Slurm REST API documentation](https://slurm.schedmd.com/rest_api.html)
* [Pyxis (NVIDIA)](https://github.com/NVIDIA/pyxis) — container support for Slurm
* [jenkins/inbound-agent](https://hub.docker.com/r/jenkins/inbound-agent) — base agent image

## Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md).

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md).
