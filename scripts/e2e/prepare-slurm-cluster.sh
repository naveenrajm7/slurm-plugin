#!/usr/bin/env bash
# Idempotent Slurm cluster setup for e2e (partition, node features, reservation).
# Requires sudo on the controller. Run via prepare-slurm.sh or directly from WSL.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-config.sh
source "${SCRIPT_DIR}/load-config.sh"

E2E_PARTITION="${E2E_PARTITION:-jenkins-e2e}"
E2E_FEATURE="${E2E_FEATURE:-jenkins-e2e}"
E2E_RESERVATION="${E2E_RESERVATION:-jenkins-e2e}"
E2E_RESERVATION_NODE="${E2E_RESERVATION_NODE:-}"
if [[ -z "${E2E_RESERVATION_NODE}" || "${E2E_RESERVATION_NODE}" == YOUR_* ]]; then
  echo "Set E2E_RESERVATION_NODE in scripts/e2e/config.env"
  exit 1
fi
E2E_RESERVATION_DURATION="${E2E_RESERVATION_DURATION:-24:00:00}"
SLURM_USER="${SLURM_USER:-$(echo "${SLURM_SSH_HOST}" | cut -d@ -f1)}"

run_remote() {
  # shellcheck disable=SC2086
  ssh ${SLURM_SSH_OPTS} "${SLURM_SSH_HOST}" "$@"
}

echo "=== Slurm e2e cluster setup on ${SLURM_SSH_HOST} ==="

run_remote "sudo bash -s" <<EOF
set -euo pipefail
PARTITION="${E2E_PARTITION}"
FEATURE="${E2E_FEATURE}"
RESERVATION="${E2E_RESERVATION}"
NODE="${E2E_RESERVATION_NODE}"
DURATION="${E2E_RESERVATION_DURATION}"
USER="${SLURM_USER}"

if ! scontrol show node "\${NODE}" | grep -q "AvailableFeatures=.*\${FEATURE}"; then
  echo "Setting AvailableFeatures=\${FEATURE} on \${NODE}"
  scontrol update NodeName="\${NODE}" AvailableFeatures="\${FEATURE}"
else
  echo "Node \${NODE} already has feature \${FEATURE}"
fi

if ! scontrol show partition "\${PARTITION}" &>/dev/null; then
  echo "Creating partition \${PARTITION}"
  scontrol create PartitionName="\${PARTITION}" Nodes="\${NODE}" Default=NO MaxTime=60 State=UP
else
  echo "Partition \${PARTITION} already exists"
fi

if [[ "${E2E_ENSURE_RESERVATION:-0}" == "1" ]]; then
  if ! scontrol show reservation "\${RESERVATION}" &>/dev/null; then
    echo "Creating reservation \${RESERVATION}"
    scontrol create reservation ReservationName="\${RESERVATION}" StartTime=now Duration="\${DURATION}" Nodes="\${NODE}" Users="\${USER}"
  else
    echo "Reservation \${RESERVATION} already exists"
  fi
else
  echo "Skipping standing reservation (pipeline creates on demand)"
fi

echo "--- sinfo ---"
sinfo -h -o '%P %a %f' | grep -E "(\${PARTITION}|PARTITION)" || sinfo -h -o '%P %a %f' | head -5
echo "--- reservation ---"
scontrol show reservation "\${RESERVATION}" | head -3
EOF

echo "Cluster setup complete."
