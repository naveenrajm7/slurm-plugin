// Static cloud template test: provisions via Jenkins label (no inline slurmJobTemplate).
pipeline {
    agent { label '${E2E_TEMPLATE_LABEL}' }
    stages {
        stage('Smoke') {
            steps {
                sh 'hostname'
                sh 'nproc || true'
                sh 'sleep 30'
                sh 'pwd'
            }
        }
    }
}
