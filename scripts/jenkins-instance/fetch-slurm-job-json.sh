#!/usr/bin/env bash
# Fetch raw slurmrestd job JSON from the controller (reads SLURM_REST_URL from config.env).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/load-config.sh"

JOBID="${1:?job id required}"
require_var SLURM_REST_URL

TOKEN="$(scontrol token lifespan=3600 2>/dev/null | sed -n 's/^SLURM_JWT=//p')"
curl -sS -H "X-Slurm-USER-TOKEN: ${TOKEN}" \
  "${SLURM_REST_URL}/slurm/v0.0.42/job/${JOBID}"
