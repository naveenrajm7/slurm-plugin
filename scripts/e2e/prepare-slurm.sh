#!/usr/bin/env bash
# Pre-flight checks on the Slurm cluster (workdir, tunnel, scontrol ping).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-config.sh
source "${SCRIPT_DIR}/load-config.sh"

WORKDIR="${E2E_TEMPLATE_WORKDIR:-/tmp/jenkins}"
TUNNEL_REMOTE_PORT="${TUNNEL_REMOTE_PORT:-5000}"

echo "Ensuring workdir ${WORKDIR}"
# shellcheck disable=SC2086
ssh ${SLURM_SSH_OPTS} "${SLURM_SSH_HOST}" "mkdir -p '${WORKDIR}' && ls -ld '${WORKDIR}'"

echo "Tunnel HTTP check (optional)"
# shellcheck disable=SC2086
ssh ${SLURM_SSH_OPTS} "${SLURM_SSH_HOST}" \
  "curl -s -m 5 -o /dev/null -w 'tunnel_http=%{http_code}\n' http://127.0.0.1:${TUNNEL_REMOTE_PORT}/jenkins/ || true"

echo "Slurm controller"
# shellcheck disable=SC2086
ssh ${SLURM_SSH_OPTS} "${SLURM_SSH_HOST}" "scontrol ping && sinfo -h -o '%P %a' | head -5"
