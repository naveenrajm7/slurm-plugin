#!/usr/bin/env bash
# Run Groovy on Jenkins script console (reads JENKINS_URL from config.env).
set -eu
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "${JENKINS_URL:-}" ]] && [[ -f "${SCRIPT_DIR}/config.env" ]]; then
  # shellcheck disable=SC1091
  source "${SCRIPT_DIR}/config.env"
fi
JENKINS_URL="${JENKINS_URL:?Set JENKINS_URL in scripts/jenkins-instance/config.env}"
SCRIPT="${1:?usage: jenkins-script.sh '<groovy>'}"
CRUMB_JSON=$(curl -fsSL "${JENKINS_URL}/crumbIssuer/api/json")
CRUMB=$(echo "$CRUMB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['crumb'])")
FIELD=$(echo "$CRUMB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['crumbRequestField'])")
curl -fsSL -X POST "${JENKINS_URL}/scriptText" \
  -H "${FIELD}: ${CRUMB}" \
  --data-urlencode "script=${SCRIPT}"
