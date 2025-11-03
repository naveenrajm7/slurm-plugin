# Slurm plugin for Jenkins


Jenkins plugin to run dynamic agents in a Slurm cluster.

Similar to the [Kubernetes plugin](https://plugins.jenkins.io/kubernetes/),
automates the scaling of Jenkins agents running in Slurm.

The plugin creates a Slurm Job for each agent started, and stops it after each build.

Agents are launched as inbound agents, so it is expected that the container connects automatically to the Jenkins controller.

Tested with sqsh image of [`jenkins/inbound-agent`](https://hub.docker.com/r/jenkins/inbound-agent),
see the [Docker image source code](https://github.com/jenkinsci/docker-agent).


# 📜 Table of Contents

- [Generic setup](#generic-setup)
- [Usage](#usage)


# Generic Setup
## Prerequisites
* A running Slurm cluster 24.11 or later
* A Jenkins instance installed
* The Jenkins Slurm plugin installed
* A token with sufficient privileges ([Slurm REST Basic usage](https://slurm.schedmd.com/rest_quickstart.html#basic_usage))

## Configuration

Fill in the Slurm plugin configuration.
In order to do that, you will open the Jenkins UI and navigate to **Manage Jenkins -> Manage Nodes and Clouds -> Configure Clouds -> Add a new cloud -> Slurm** and enter the *Slurm REST URL* and *Jenkins URL* appropriately.

Supported credentials include:

* Secret File (with token)

By default, jenkins agent uses **WebSocket** and connect over HTTP(s).

To test this connection is successful you can use the **Test Connection** button to ensure there is
adequate communication from Jenkins to the Slurm cluster, as seen below

![image](images/cloud-configuration.png)

## Static job templates


# Usage
## Overview

The Slurm plugin allocates Jenkins agents as Slurm jobs.

Within these jobs, there is always one process running the Jenkins inbound agent, launched via `srun -N1 -n1` to ensure a single agent connection per job.

The agent process runs either directly on the compute node or inside a Pyxis container if container support is configured.

When multi-node allocations are requested (`minimum_nodes > 1`), the Jenkins agent runs on the first node only, while your pipeline commands can utilize the full SLURM job allocation using `srun` or MPI launchers.

## Using a label

Job templates defined using the user interface declare a label. When a freestyle job or a pipeline job using
`node('some-label')` uses a label declared by a job template, the Slurm Cloud allocates a new job to run the
Jenkins agent.

It should be noted that the main reason to use the global job template definition is to migrate a huge corpus of
existing projects (including freestyle) to run on Slurm without changing job definitions.
New users setting up new Slurm builds should use the `jobTemplate` step as shown in the example snippets
[here](examples).

## Using the pipeline step

## Issues

TODO Decide where you're going to host your issues, the default is Jenkins JIRA, but you can also enable GitHub issues,
If you use GitHub issues there's no need for this section; else add the following line:

Report issues and enhancements in the [Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

TODO review the default [CONTRIBUTING](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) file and make sure it is appropriate for your plugin, if not then add your own one adapted from the base file

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

# Related Projects

1. [Kubernetes plugin](https://github.com/jenkinsci/kubernetes-plugin)