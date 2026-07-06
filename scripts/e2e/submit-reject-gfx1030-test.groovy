// E2e: gfx1030 on jenkins-e2e partition — Slurm rejects at submit (no job ID).
pipeline {
    agent none
    stages {
        stage('Submit reject') {
            agent {
                slurm {
                    cloud 'my-slurm-cloud'
                    label 'gfx1030-submit-reject-test'
                    json '''
                    {
                        "job": {
                            "partition": "jenkins-e2e",
                            "constraints": "jenkins-e2e",
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
                echo 'Should not reach here — submit should fail'
            }
        }
    }
}
