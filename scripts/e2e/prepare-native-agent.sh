#!/usr/bin/env bash
# Install Java + agent.jar on Slurm compute nodes for native (non-Pyxis) agent launch.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-config.sh
source "${SCRIPT_DIR}/load-config.sh"

AGENT_JAR_DIR="${E2E_NATIVE_AGENT_JAR_DIR:-/opt/jenkins}"
AGENT_JAR_PATH="${AGENT_JAR_DIR}/agent.jar"
JAVA_PACKAGE="${E2E_NATIVE_JAVA_PACKAGE:-java-17-openjdk-headless}"
# URL reachable from the Slurm controller (tunnel endpoint when using local hpi:run)
AGENT_JAR_SOURCE_URL="${SLURM_JENKINS_AGENT_URL:-${JENKINS_URL:-http://localhost:8080/jenkins/}}"

run_remote() {
  # shellcheck disable=SC2086
  ssh ${SLURM_SSH_OPTS} "${SLURM_SSH_HOST}" "$@"
}

echo "=== Native agent prep on ${SLURM_SSH_HOST} ==="

run_remote "sudo bash -s" <<EOF
set -euo pipefail
AGENT_JAR_DIR="${AGENT_JAR_DIR}"
AGENT_JAR_PATH="${AGENT_JAR_PATH}"
JAVA_PACKAGE="${JAVA_PACKAGE}"
AGENT_JAR_SOURCE_URL="${AGENT_JAR_SOURCE_URL}"

if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "\${JAVA_PACKAGE}" curl ca-certificates
elif command -v yum >/dev/null 2>&1; then
  yum install -y "\${JAVA_PACKAGE}" curl ca-certificates
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y "\${JAVA_PACKAGE}" curl ca-certificates
else
  echo "No supported package manager found; ensure Java and curl are installed" >&2
  exit 1
fi

mkdir -p "\${AGENT_JAR_DIR}"
if [ ! -f "\${AGENT_JAR_PATH}" ]; then
  echo "Downloading agent.jar to \${AGENT_JAR_PATH}"
  curl -fsSL -o "\${AGENT_JAR_PATH}" "\${AGENT_JAR_SOURCE_URL%/}/jnlpJars/agent.jar"
fi
chmod 755 "\${AGENT_JAR_DIR}"
chmod 644 "\${AGENT_JAR_PATH}"

echo "--- java ---"
java -version
echo "--- agent.jar ---"
ls -l "\${AGENT_JAR_PATH}"
EOF

echo "Native agent prep complete."
