#!/usr/bin/env bash
# Source shared jenkins-instance config for bash scripts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/config.env"

if [[ ! -f "${CONFIG_FILE}" ]]; then
  echo "Missing ${CONFIG_FILE}" >&2
  echo "Copy config.env.example to config.env and set your values (not committed)." >&2
  exit 1
fi

# shellcheck disable=SC1091
source "${CONFIG_FILE}"

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]] || [[ "${!name}" == YOUR_* ]] || [[ "${!name}" == your-* ]] || [[ "${!name}" == CHANGE_ME ]]; then
    echo "Set ${name} in ${CONFIG_FILE}" >&2
    exit 1
  fi
}

require_var JENKINS_SSH_HOST
require_var SLURM_SSH_HOST
