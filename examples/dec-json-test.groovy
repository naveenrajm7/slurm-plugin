// Declarative pipeline e2e: inline JSON slurm {} agents (CPU + optional GPU/Pyxis).
// Copy into your Jenkins folder job; replace cloud name, container paths, and labels.

pipeline {
    agent none
    stages {
        stage('CPU Task') {
            agent {
                slurm {
                    cloud 'my-slurm-cloud'
                    label 'cpu-test'
                    json '''
                    {
                        "job": {
                            "cpus_per_task": 16,
                            "memory_per_node": 32768,
                            "current_working_directory": "/tmp/jenkins"
                        },
                        "pyxis": {
                            "container_image": "/path/to/jenkins-inbound-agent.sqsh",
                            "container_mount_home": true,
                            "container_writable": false,
                            "container_remap_root": true
                        }
                    }
                    '''
                }
            }
            steps {
                sh 'hostname'
                sh 'nproc'
                sh 'sleep 30'
                sh 'pwd'
            }
        }

        stage('GPU Task') {
            agent {
                slurm {
                    cloud 'my-slurm-cloud'
                    label 'gpu-test'
                    json '''
                    {
                        "job": {
                            "cpus_per_task": 8,
                            "memory_per_node": 16384,
                            "current_working_directory": "/tmp/jenkins",
                            "tres_per_job": "gres/gpu:1"
                        },
                        "pyxis": {
                            "container_image": "/path/to/rocm-jenkins.sqsh",
                            "container_mount_home": true,
                            "container_writable": false,
                            "container_remap_root": true
                        }
                    }
                    '''
                }
            }
            steps {
                sh 'hostname'
                sh 'sleep 30'
                sh 'rocm-smi || nvidia-smi || true'
            }
        }
    }
}
