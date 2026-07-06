#!/usr/bin/env bash
# Start WSL SSH reverse tunnel so Slurm agents can reach local Jenkins.
# Usage: ./start-tunnel.sh  (keep terminal open)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-config.sh
source "${SCRIPT_DIR}/load-config.sh"

TUNNEL_REMOTE_PORT="${TUNNEL_REMOTE_PORT:-5000}"
TUNNEL_LOCAL_PORT="${TUNNEL_LOCAL_PORT:-8080}"
TUNNEL_REMOTE_BIND="${TUNNEL_REMOTE_BIND:-127.0.0.1}"

echo "Tunnel: ${SLURM_SSH_HOST} ${TUNNEL_REMOTE_BIND}:${TUNNEL_REMOTE_PORT} -> localhost:${TUNNEL_LOCAL_PORT}"

# shellcheck disable=SC2086
exec ssh -N \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=10 \
  $SLURM_SSH_OPTS \
  -R "${TUNNEL_REMOTE_BIND}:${TUNNEL_REMOTE_PORT}:127.0.0.1:${TUNNEL_LOCAL_PORT}" \
  "${SLURM_SSH_HOST}"
