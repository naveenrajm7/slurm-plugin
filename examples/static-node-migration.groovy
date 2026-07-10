// Static-node → Slurm migration examples (gfx1100 / multi-GPU workstation pattern).
//
// Reference static node: gpu-workstation-01 (4x gfx1100, label 4gfx1100, remoteFS /var/jenkins_home).
// Template definitions (JCasC fields + Slurm job JSON): examples/static-node-migration.json
//
// Migration flow:
//   1. Add Slurm cloud template with same labels (minus auto-disabled) + required_nodes for cutover testing
//   2. Run representative jobs on Slurm template in parallel with the static node
//   3. Drain static node (e.g. add auto-disabled) once Slurm path is validated
//   4. Remove required_nodes when the host joins the general Slurm pool
//
// Do NOT port DOCKER_GPU_MASK_* / MULTI_GPU env vars — Slurm binds GPUs per job allocation.

def slurmCloud = 'my-cluster'
def workdir = '/var/jenkins_home'
def gpuPartition = 'gpu'
def gpuTresType = 'gfx1100'          // verify: sacctmgr show tres / scontrol show node
def transitionalNode = 'gpu-workstation-01'  // omit after pool enrollment
def ccachePath = '/files/common/ccache'

def singleGpuEnv = """
"environment": [
  "NODE_CCACHE_PATH=${ccachePath}",
  "DOCKER_API_VERSION=1.44",
  "RENDER_GID=109"
]
"""

pipeline {
    agent none
    stages {
        stage('Cloud template by label') {
            agent { label 'gfx1100' }
            steps {
                sh 'hostname && nproc'
                sh 'test -d "${ccachePath}" || echo "ccache path missing (adjust template env)"'
                // Slurm sets ROCR/HIP device visibility for the allocated GPU(s).
                // Pipelines must not rely on DOCKER_GPU_MASK_${EXECUTOR_NUMBER}.
            }
        }

        stage('Declarative: transitional pin to static host') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'gfx1100'
                    json """
                    {
                      "job": {
                        "partition": "${gpuPartition}",
                        "required_nodes": ["${transitionalNode}"],
                        "cpus_per_task": 8,
                        "memory_per_node": 32768,
                        "time_limit": 180,
                        "tres_per_job": "gres/gpu:${gpuTresType}:1",
                        "current_working_directory": "${workdir}",
                        ${singleGpuEnv}
                      }
                    }
                    """
                }
            }
            steps {
                sh "test \"\$(hostname -s)\" = \"${transitionalNode}\""
            }
        }

        stage('Declarative: pool scheduling (no required_nodes)') {
            agent {
                slurm {
                    cloud slurmCloud
                    json """
                    {
                      "job": {
                        "partition": "${gpuPartition}",
                        "cpus_per_task": 8,
                        "memory_per_node": 32768,
                        "time_limit": 180,
                        "tres_per_job": "gres/gpu:${gpuTresType}:1",
                        "current_working_directory": "${workdir}",
                        ${singleGpuEnv}
                      }
                    }
                    """
                }
            }
            steps {
                sh 'hostname'
            }
        }

        stage('All 4 GPUs (replaces 4gfx1100 static label)') {
            agent {
                slurm {
                    cloud slurmCloud
                    label '4gfx1100'
                    json """
                    {
                      "job": {
                        "partition": "${gpuPartition}",
                        "cpus_per_task": 32,
                        "memory_per_node": 131072,
                        "time_limit": 180,
                        "tres_per_job": "gres/gpu:${gpuTresType}:4",
                        "current_working_directory": "${workdir}",
                        ${singleGpuEnv}
                      }
                    }
                    """
                }
            }
            steps {
                sh 'hostname'
                // Optional: assert GPU count when rocm-smi is available
                sh 'command -v rocm-smi >/dev/null && rocm-smi --showid || true'
            }
        }

        stage('slurmJobTemplate step override') {
            steps {
                slurmJobTemplate(
                    cloud: slurmCloud,
                    json: """
                    {
                      "job": {
                        "partition": "${gpuPartition}",
                        "cpus_per_task": 8,
                        "memory_per_node": 32768,
                        "time_limit": 60,
                        "tres_per_job": "gres/gpu:${gpuTresType}:1",
                        "current_working_directory": "${workdir}",
                        ${singleGpuEnv}
                      }
                    }
                    """
                ) {
                    sh 'hostname'
                }
            }
        }
    }
}
