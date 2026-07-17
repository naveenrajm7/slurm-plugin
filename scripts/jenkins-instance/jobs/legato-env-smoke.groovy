// Smoke: legato-style Slurm template with node env vars (NUMBER_OF_EXECUTORS=1, ccache paths).
pipeline {
  agent {
    label '${LEGATO_LABEL}'
  }
  stages {
    stage('Print template env vars') {
      steps {
        sh '''
          echo "Jenkins node: ${NODE_NAME:-<unset>}"
          echo "Jenkins EXECUTOR_NUMBER: ${EXECUTOR_NUMBER:-n/a}"
          set -x
          echo "=== host $(hostname) ==="
          echo "NUMBER_OF_EXECUTORS=${NUMBER_OF_EXECUTORS:-<unset>}"
          echo "CCACHE_MAXSIZE=${CCACHE_MAXSIZE:-<unset>}"
          echo "NODE_CCACHE_PATH=${NODE_CCACHE_PATH:-<unset>}"
          echo "NODE_HAS_CCACHE=${NODE_HAS_CCACHE:-<unset>}"
          echo "SLURM_JOB_ID=${SLURM_JOB_ID:-<unset>}"
          test "${NUMBER_OF_EXECUTORS}" = "1" || {
            echo "ERROR: expected NUMBER_OF_EXECUTORS=1 from template env" >&2
            exit 1
          }
        '''
      }
    }
  }
}
