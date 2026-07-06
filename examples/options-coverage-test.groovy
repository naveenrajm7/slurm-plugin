// End-to-end coverage for Slurm job options (declarative JSON path).
// Pair with cloud template label "absol-options" for static-template path tests.
// Cluster-specific values: cloud name, container image, node names, account, qos.

def slurmCloud = 'cgy-absol'
def workdir = '/tmp/jenkins'
def cpuImage = '/home/AMD/nmuthura/jenkins+inbound-agent+latest.sqsh'
def gpuImage = '/home/AMD/nmuthura/rocm+jenkins.sqsh'
def slurmAccount = 'ags'
def slurmQos = 'normal'
def targetNode = 'cgy-absol'
def e2ePartition = 'jenkins-e2e'
def e2eFeature = 'jenkins-e2e'
def e2eReservation = 'jenkins-e2e'

pipeline {
    agent none
    stages {
        stage('Declarative: core resources') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-core'
                    json """
                    {
                        "job": {
                            "partition": "${e2ePartition}",
                            "account": "${slurmAccount}",
                            "qos": "${slurmQos}",
                            "cpus_per_task": 4,
                            "memory_per_node": 4096,
                            "time_limit": 30,
                            "current_working_directory": "${workdir}",
                            "comment": "jenkins-e2e-core"
                        },
                        "pyxis": {
                            "container_image": "${cpuImage}",
                            "container_mount_home": true,
                            "container_remap_root": true
                        }
                    }
                    """
                }
            }
            steps {
                sh 'hostname'
                sh 'test "$(nproc)" -ge 4'
                sh 'test -n "$SLURM_JOB_ACCOUNT" && echo account=$SLURM_JOB_ACCOUNT'
            }
        }

        stage('Declarative: node selection') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-nodes'
                    json """
                    {
                        "job": {
                            "partition": "${e2ePartition}",
                            "cpus_per_task": 2,
                            "memory_per_node": 2048,
                            "time_limit": 20,
                            "current_working_directory": "${workdir}",
                            "required_nodes": "cgy-absol",
                            "excluded_nodes": "cgy-clefairy,cgy-geodude"
                        },
                        "pyxis": {
                            "container_image": "${cpuImage}",
                            "container_mount_home": true,
                            "container_remap_root": true
                        }
                    }
                    """
                }
            }
            steps {
                sh "test \"\$(hostname -s)\" = \"${targetNode}\""
            }
        }

        stage('Declarative: dedicated partition') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-partition'
                    json """
                    {
                        "job": {
                            "partition": "${e2ePartition}",
                            "cpus_per_task": 2,
                            "memory_per_node": 2048,
                            "time_limit": 15,
                            "current_working_directory": "${workdir}",
                            "constraints": "${e2eFeature}"
                        },
                        "pyxis": {
                            "container_image": "${cpuImage}",
                            "container_mount_home": true,
                            "container_remap_root": true
                        }
                    }
                    """
                }
            }
            steps {
                sh 'test "$SLURM_JOB_PARTITION" = "' + e2ePartition + '"'
                sh 'hostname'
            }
        }

        stage('Declarative: reservation setup') {
            agent any
            steps {
                powershell '''
                    wsl -e ssh -o BatchMode=yes nmuthura@cgy-absol "sudo scontrol delete reservation=jenkins-e2e 2>/dev/null || true; sudo scontrol create reservation ReservationName=jenkins-e2e StartTime=now Duration=02:00:00 Nodes=cgy-absol Users=nmuthura"
                '''
            }
        }

        stage('Declarative: reservation') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-reservation'
                    json """
                    {
                        "job": {
                            "partition": "${e2ePartition}",
                            "reservation": "${e2eReservation}",
                            "cpus_per_task": 1,
                            "memory_per_node": 1024,
                            "time_limit": 15,
                            "current_working_directory": "${workdir}"
                        },
                        "pyxis": {
                            "container_image": "${cpuImage}",
                            "container_mount_home": true,
                            "container_remap_root": true
                        }
                    }
                    """
                }
            }
            steps {
                sh 'test -n "$SLURM_JOB_PARTITION"'
                sh 'hostname'
            }
        }

        stage('Declarative: reservation teardown') {
            agent any
            steps {
                powershell 'wsl -e ssh -o BatchMode=yes nmuthura@cgy-absol "sudo scontrol delete reservation=jenkins-e2e 2>/dev/null || true"'
            }
        }

        stage('Declarative: environment JSON array') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-env-json'
                    json """
                    {
                        "job": {
                            "partition": "${e2ePartition}",
                            "cpus_per_task": 1,
                            "memory_per_node": 1024,
                            "time_limit": 15,
                            "current_working_directory": "${workdir}",
                            "environment": ["JENKINS_E2E_MARKER=options-coverage", "CUSTOM_VAR=42"]
                        },
                        "pyxis": {
                            "container_image": "${cpuImage}",
                            "container_mount_home": true,
                            "container_remap_root": true
                        }
                    }
                    """
                }
            }
            steps {
                sh 'test "$JENKINS_E2E_MARKER" = "options-coverage"'
                sh 'test "$CUSTOM_VAR" = "42"'
            }
        }

        stage('Declarative: environment via template') {
            agent { label 'absol-options' }
            steps {
                sh 'test "$JENKINS_E2E_TEMPLATE" = "static-template"'
            }
        }

        stage('Declarative: TRES GPU') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-gpu'
                    json """
                    {
                        "job": {
                            "partition": "${e2ePartition}",
                            "cpus_per_task": 4,
                            "memory_per_node": 8192,
                            "time_limit": 20,
                            "current_working_directory": "${workdir}",
                            "tres_per_job": "gres/gpu:gfx906:1",
                            "required_nodes": "cgy-absol"
                        },
                        "pyxis": {
                            "container_image": "${gpuImage}",
                            "container_mount_home": true,
                            "container_remap_root": true
                        }
                    }
                    """
                }
            }
            steps {
                sh 'hostname'
                sh 'rocm-smi || true'
            }
        }

        stage('Declarative: property-based parity') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-props'
                    partition e2ePartition
                    account 'ags'
                    qos 'normal'
                    cpus 2
                    memory '2048M'
                    time '20'
                    workingDir workdir
                    containerImage cpuImage
                    containerMountHome true
                }
            }
            steps {
                sh 'hostname'
                sh 'test "$(nproc)" -ge 2'
            }
        }

        stage('Template: static label') {
            agent { label 'absol-options' }
            steps {
                sh 'hostname'
                sh 'test "$JENKINS_E2E_TEMPLATE" = "static-template"'
                sh 'test "$(nproc)" -ge 2'
            }
        }
    }
}
