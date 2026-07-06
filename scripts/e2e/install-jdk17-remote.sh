#!/usr/bin/env bash
# Install portable JDK 17 under /opt/jenkins for native agent e2e.
set -euo pipefail

JDK_DIR=/opt/jenkins/jdk-17
if [[ -x "${JDK_DIR}/bin/java" ]]; then
  "${JDK_DIR}/bin/java" -version
  exit 0
fi

mkdir -p /opt/jenkins
curl -fsSL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o /tmp/jdk17.tar.gz
tar -xzf /tmp/jdk17.tar.gz -C /opt/jenkins
rm -f /tmp/jdk17.tar.gz
EXTRACTED=$(find /opt/jenkins -maxdepth 1 -type d -name 'jdk-17*' | head -1)
if [[ -z "${EXTRACTED}" ]]; then
  echo "JDK extract failed" >&2
  exit 1
fi
if [[ "${EXTRACTED}" != "${JDK_DIR}" ]]; then
  rm -rf "${JDK_DIR}"
  mv "${EXTRACTED}" "${JDK_DIR}"
fi
chmod -R a+rX "${JDK_DIR}"
"${JDK_DIR}/bin/java" -version
