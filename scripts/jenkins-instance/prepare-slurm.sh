#!/usr/bin/env bash
# Pre-flight checks on the Slurm cluster (workdir, controller ping).
# No SSH tunnel check — dedicated Jenkins is directly reachable by agents.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-config.sh
source "${SCRIPT_DIR}/load-config.sh"

WORKDIR="${E2E_TEMPLATE_WORKDIR:-/tmp/jenkins}"

echo "Ensuring workdir ${WORKDIR}"
# shellcheck disable=SC2086
ssh ${SLURM_SSH_OPTS} "${SLURM_SSH_HOST}" "mkdir -p '${WORKDIR}' && ls -ld '${WORKDIR}'"

echo "Agent Jenkins URL (from config): ${JENKINS_AGENT_URL:-unset}"
if [[ -n "${JENKINS_AGENT_URL:-}" ]]; then
  # shellcheck disable=SC2086
  ssh ${SLURM_SSH_OPTS} "${SLURM_SSH_HOST}" \
    "curl -s -m 5 -o /dev/null -w 'agent_url_http=%{http_code}\n' '${JENKINS_AGENT_URL}' || true"
fi

echo "Slurm controller"
# shellcheck disable=SC2086
ssh ${SLURM_SSH_OPTS} "${SLURM_SSH_HOST}" "scontrol ping && sinfo -h -o '%P %a' | head -5"
