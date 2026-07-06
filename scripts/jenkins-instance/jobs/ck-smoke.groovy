// Minimal CK label smoke — validates Slurm provisioning without the CK shared library.
pipeline {
  agent none
  stages {
    stage('nogpu') {
      steps {
        node('(rocmtest || miopen) && nogpu') {
          sh '''
            set -x
            echo "=== nogpu smoke on $(hostname) ==="
            echo "NODE_NAME=$NODE_NAME"
            scontrol show job "${SLURM_JOB_ID:-}" 2>/dev/null | head -20 || true
            java -version 2>&1 || true
            test -f /opt/jenkins/agent.jar && echo agent.jar:ok || echo agent.jar:missing
          '''
        }
      }
    }
    stage('gfx950') {
      steps {
        node('(rocmtest || miopen) && gfx950') {
          sh '''
            set -x
            echo "=== gfx950 smoke on $(hostname) ==="
            echo "NODE_NAME=$NODE_NAME"
            scontrol show job "${SLURM_JOB_ID:-}" 2>/dev/null | head -20 || true
            rocminfo 2>/dev/null | grep -m1 gfx950 || echo "rocminfo: no gfx950 line (may be OK if GPU present)"
          '''
        }
      }
    }
  }
}
