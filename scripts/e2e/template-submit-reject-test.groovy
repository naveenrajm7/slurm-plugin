// Static cloud template test: provisions via Jenkins label only (no slurm {} block).
// Requires a cloud template labeled 'bad-gfx-template' with unobtainable gfx1030.
pipeline {
    agent { label 'bad-gfx-template' }
    stages {
        stage('Should not run') {
            steps {
                sh 'hostname'
            }
        }
    }
}
