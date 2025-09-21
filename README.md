# SLURM Plugin for Jenkins

## Introduction

This plugin allows Jenkins to use SLURM (Simple Linux Utility for Resource Management) as a cloud provider to dynamically provision build agents. Similar to the Kubernetes plugin, this enables Jenkins to submit jobs to SLURM clusters and use the resulting compute resources as Jenkins agents.

## Getting started

After installing this plugin:

1. Go to **Manage Jenkins** → **Configure System**
2. Scroll down to **Cloud** section
3. Click **Add a new cloud** and select **SLURM**
4. Configure your SLURM cluster connection details:
   - SLURM controller hostname
   - SSH credentials for accessing the cluster
   - Default job template settings
5. Save the configuration

Jenkins will now be able to submit jobs to your SLURM cluster and use them as build agents.

## Issues

TODO Decide where you're going to host your issues, the default is Jenkins JIRA, but you can also enable GitHub issues,
If you use GitHub issues there's no need for this section; else add the following line:

Report issues and enhancements in the [Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

TODO review the default [CONTRIBUTING](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) file and make sure it is appropriate for your plugin, if not then add your own one adapted from the base file

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

