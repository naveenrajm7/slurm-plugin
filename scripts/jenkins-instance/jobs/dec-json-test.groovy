// Declarative slurm {} agent test (CPU stage only for harness smoke).
pipeline {
    agent none
    stages {
        stage('CPU Task') {
            agent {
                slurm {
                    cloud '${SLURM_CLOUD_NAME}'
                    label 'cpu-test'
                    json '''
                    {
                        "job": {
                            "partition": "${E2E_TEMPLATE_PARTITION}",
                            "reservation": "${E2E_RESERVATION}",
                            "cpus_per_task": ${E2E_TEMPLATE_CPUS},
                            "memory_per_node": ${E2E_TEMPLATE_MEMORY_MB},
                            "current_working_directory": "${E2E_TEMPLATE_WORKDIR}"
                        },
                        "pyxis": {
                            "container_image": "${E2E_CPU_CONTAINER_IMAGE}",
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
    }
}
