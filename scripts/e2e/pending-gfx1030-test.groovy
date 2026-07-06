// E2e: request gfx1030 on a node that only has gfx906 — job should stay PENDING.
pipeline {
    agent none
    stages {
        stage('Unobtainable GPU') {
            agent {
                slurm {
                    cloud 'my-slurm-cloud'
                    label 'gfx1030-pending-test'
                    json '''
                    {
                        "job": {
                            "partition": "defq",
                            "cpus_per_task": 2,
                            "memory_per_node": 4096,
                            "time_limit": 60,
                            "current_working_directory": "/tmp/jenkins",
                            "tres_per_job": "gres/gpu:gfx1030:1"
                        }
                    }
                    '''
                }
            }
            steps {
                sh 'hostname'
                echo 'Should not reach here while gfx1030 is unavailable'
            }
        }
    }
}
