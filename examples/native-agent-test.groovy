// Native (non-Pyxis) agent launch e2e smoke test.
// Requires Java 17+ and agent.jar on compute nodes — see docs/NATIVE_AGENT_SETUP.md
// and scripts/e2e/prepare-native-agent.sh.

def slurmCloud = 'cgy-absol'
def workdir = '/tmp/jenkins'
def e2ePartition = 'jenkins-e2e'
def e2eFeature = 'jenkins-e2e'
def agentJava = '/opt/jenkins/jdk-17/bin/java'
def agentJar = '/opt/jenkins/agent.jar'

pipeline {
    agent none
    stages {
        stage('Declarative JSON (cloud agent defaults)') {
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
                        }
                    }
                    """
                }
            }
            steps {
                sh 'hostname && test -f /opt/jenkins/agent.jar'
                echo 'Connected via cloud-level native agent defaults'
            }
        }
        stage('Declarative properties (explicit paths)') {
            agent {
                slurm {
                    cloud slurmCloud
                    label 'e2e-native-props'
                    partition e2ePartition
                    workingDir workdir
                    cpus 2
                    memory '2048M'
                    time '30'
                    constraints e2eFeature
                    javaPath agentJava
                    jarPath agentJar
                }
            }
            steps {
                sh "${agentJava} -version"
                echo 'Connected via declarative javaPath/jarPath properties'
            }
        }
    }
}
