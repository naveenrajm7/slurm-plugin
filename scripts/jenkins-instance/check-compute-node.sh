#!/usr/bin/env bash
# Verify compute-node reachability to Jenkins agent URL and basic tooling.
set -eu
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/config.env"

NODE="${1:?usage: $0 COMPUTE_NODE_HOSTNAME}"
SLURM_SSH_HOST="${SLURM_SSH_HOST:?Set SLURM_SSH_HOST in config.env}"
JENKINS_AGENT_URL="${JENKINS_AGENT_URL:?Set JENKINS_AGENT_URL in config.env}"

ssh -o ConnectTimeout=15 "${SLURM_SSH_HOST}" bash -s "$NODE" "$JENKINS_AGENT_URL" <<'EOS'
set -eu
NODE="$1"
AGENT_URL="$2"
ssh -o ConnectTimeout=15 "$NODE" bash -s "$AGENT_URL" <<'INNER'
set -eu
AGENT_URL="$1"
echo "=== node: $(hostname) ==="
curl -fsSL -o /dev/null -w "jenkins_http=%{http_code}\n" "${AGENT_URL%/}/jnlpJars/agent.jar" || echo "curl failed"
command -v java >/dev/null && java -version 2>&1 | head -1 || echo "java: missing"
INNER
EOS
