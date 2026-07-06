# Native agent setup on Slurm compute nodes

Checklist for running the Jenkins Slurm plugin **without Pyxis/Enroot**. Use this when
compute nodes run the inbound agent directly on the host filesystem.

## Prerequisites

| Requirement | Notes |
|-------------|--------|
| **Java 17+** | Current Jenkins remoting requires Java 17 (class file 61+). Java 11 on the node is not sufficient. |
| **`agent.jar`** | Pre-installed on a shared path, or use `download_jar: true` / cloud **Download Agent JAR** |
| **Writable workdir** | e.g. `/tmp/jenkins` — maps to Slurm `current_working_directory` and Jenkins `remoteFS` |
| **Network to Jenkins** | Compute nodes must reach the cloud **Jenkins URL** (WebSocket inbound agent) |
| **`curl` or `wget`** | Only if using download-at-runtime mode |

## Recommended layout

```
/opt/jenkins/
├── agent.jar              # from {jenkins_url}/jnlpJars/agent.jar
└── jdk-17/                # optional portable JDK (e.g. Eclipse Temurin)
    └── bin/java
```

## One-time node prep (example)

On each compute node (or the controller if jobs run there), as root:

```bash
# Java 17 (RHEL example — use your site’s module/spack/apt equivalent)
curl -fsSL https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse \
  | tar -xz -C /opt/jenkins
mv /opt/jenkins/jdk-17* /opt/jenkins/jdk-17

# agent.jar (from a URL compute nodes can reach)
mkdir -p /opt/jenkins
curl -fsSL -o /opt/jenkins/agent.jar http://<jenkins-host>:<port>/jenkins/jnlpJars/agent.jar
chmod 644 /opt/jenkins/agent.jar

# Slurm workdir
mkdir -p /tmp/jenkins && chmod 1777 /tmp/jenkins   # or per-site policy
```

For local dev with `mvn hpi:run` + SSH tunnel, download `agent.jar` on the Slurm controller via the tunnel endpoint (`http://127.0.0.1:5000/jenkins/jnlpJars/agent.jar`).

Automated scripts in this repo:

- `scripts/e2e/install-jdk17-remote.sh` — portable JDK 17 under `/opt/jenkins/jdk-17`
- `scripts/e2e/prepare-native-agent.sh` — installs Java + downloads `agent.jar` (uses `config.env`)

## Jenkins configuration

1. **Cloud** → **Default Agent Launch**: set `java_path` and `jar_path` (applies to all templates on that cloud).
2. **Job template** → **Agent Launch**: optional per-template overrides.
3. **Pipeline** — declarative properties or JSON `agent` block (overrides cloud/template).

Configuration priority (lowest → highest): **cloud defaults** → **template** → **pipeline JSON / properties**.

## Verify from a compute node

```bash
/opt/jenkins/jdk-17/bin/java -version    # must report 17+
test -f /opt/jenkins/agent.jar
curl -fsSL -o /dev/null http://<jenkins-url>/jenkins/
```

## Slurm job script (generated)

Without Pyxis, the plugin emits roughly:

```bash
#!/bin/bash
set -euo pipefail
srun -N1 -n1 /opt/jenkins/jdk-17/bin/java -jar /opt/jenkins/agent.jar \
  -url http://jenkins:8080/jenkins/ -secret … -name … -webSocket -workDir /tmp/{agent}
```

With **Download Agent JAR** enabled, the JAR is cached under `{workdir}/agent.jar` on first run.

## Troubleshooting

| Symptom | Likely cause |
|---------|----------------|
| `UnsupportedClassVersionError` | Java &lt; 17 on the node |
| Job exits immediately, empty logs | Workdir missing or not writable |
| `Agent launch is not configured` | No Pyxis, no cloud/template/pipeline native settings |
| Agent never connects | Jenkins URL unreachable from compute nodes; check tunnel/firewall |

See also [README.md](../README.md#running-without-pyxis--containers) and [JSON_CONFIGURATION.md](JSON_CONFIGURATION.md).
