// Native (non-Pyxis) agent launch e2e smoke test.
// Requires Java + agent.jar on compute nodes (see scripts/e2e/prepare-native-agent.sh).

def slurmCloud = 'cgy-absol'
def workdir = '/tmp/jenkins'
def e2ePartition = 'jenkins-e2e'
def e2eFeature = 'jenkins-e2e'

pipeline {
    agent none
    stages {
        stage('Native declarative agent') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-native'
                    json """
                    {
                        "job": {
                            "partition": "${e2ePartition}",
                            "current_working_directory": "${workdir}",
                            "cpus_per_task": 2,
                            "memory_per_node": 2048,
                            "time_limit": 30,
                            "constraints": "${e2eFeature}"
                        },
                        "agent": {
                            "java_path": "/opt/jenkins/jdk-17/bin/java",
                            "jar_path": "/opt/jenkins/agent.jar"
                        }
                    }
                    """
                }
            }
            steps {
                sh 'hostname && java -version && test -f /opt/jenkins/agent.jar'
                echo 'Native Slurm agent connected without Pyxis'
            }
        }
    }
}
