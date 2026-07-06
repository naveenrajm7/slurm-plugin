# Dedicated Jenkins instance harness

Production-style testing: build `target/slurm.hpi`, deploy Jenkins LTS in Docker on a
remote host, install the plugin, configure the Slurm cloud, and run the same smoke jobs as
`scripts/e2e/` — **without** an SSH reverse tunnel.

## Compared to local `scripts/e2e/`

| | Local e2e (`hpi:run`) | Instance harness |
|--|----------------------|------------------|
| Jenkins | `mvn hpi:run` on your machine | Docker LTS on remote host |
| Plugin | Dev classpath | Uploaded `.hpi` |
| Security | None (dev mode) | Admin user + password |
| Agent URL | Tunnel `localhost:5000` → laptop | Direct `http://<host>:8282/` |
| Config | `scripts/e2e/config.env` | `scripts/jenkins-instance/config.env` |

## Prerequisites

- SSH access to the Jenkins host (`JENKINS_SSH_HOST`)
- Docker + Docker Compose on that host
- WSL (for SSH/SCP from Windows, same as local e2e)
- Slurm cluster with `slurmrestd` (same cluster as local e2e is fine)
- Compute nodes must reach `JENKINS_AGENT_URL` over the network (no tunnel)

## Quick start

```powershell
# 1. Copy and edit config (gitignored)
cp scripts/jenkins-instance/config.env.example scripts/jenkins-instance/config.env

# 2. Deploy Jenkins + install plugin (builds slurm.hpi)
pwsh -File scripts/jenkins-instance/deploy.ps1

# 3. Configure Slurm cloud, JWT credential, folder, and jobs
pwsh -File scripts/jenkins-instance/configure.ps1

# 4. Run smoke tests
pwsh -File scripts/jenkins-instance/run-e2e.ps1 -SkipDeploy
```

Or one shot after initial setup:

```powershell
pwsh -File scripts/jenkins-instance/run-e2e.ps1 -Configure
```

## Configuration (`config.env`)

Copy `config.env.example` → `config.env`. Key values:

| Variable | Purpose |
|----------|---------|
| `JENKINS_SSH_HOST` | `user@host` for Docker deploy |
| `JENKINS_HOSTNAME` / `JENKINS_URL` | Browser and REST API base URL |
| `JENKINS_AGENT_URL` | Slurm cloud **Jenkins URL** (agents connect here) |
| `JENKINS_ADMIN_USER` / `JENKINS_ADMIN_PASSWORD` | Admin login |
| `SLURM_REST_URL` | `slurmrestd` endpoint |
| `SLURM_JWT_TOKEN` | Optional; if unset, fetched via `scontrol token` on `SLURM_SSH_HOST` |
| `E2E_*` | Folder, job names, template label, container images |
| `E2E_TEMPLATE_PARTITION` | Slurm partition (e.g. `jenkins-e2e`) |
| `E2E_TIME_LIMIT_MINUTES` | Must be ≤ partition `MaxTime` (e.g. 55 for 1h cap) |
| `E2E_RESERVATION` | Optional Slurm reservation name |

**Agent URL:** set `JENKINS_AGENT_URL` to a URL compute nodes can reach, e.g.
`http://cgy-absol:8282/` when Jenkins Docker runs on the Slurm controller and port 8282
is open on the cluster network.

**Java version:** use `jenkins/jenkins:lts-jdk17` so the controller matches Java 17 in the
cluster's inbound-agent `.sqsh` image. JDK 21 controller + Java 17 agent fails at connect time.

## Scripts

| Script | Purpose |
|--------|---------|
| `deploy.ps1` | SCP Docker files, `docker compose up`, wait for Jenkins, upload `slurm.hpi` |
| `configure.ps1` | Slurm cloud + JWT credential + e2e folder/jobs (Script Console) |
| `run-e2e.ps1` | Build, optional deploy/configure, trigger jobs, poll until done |
| `prepare-slurm.sh` | Ensure workdir exists; curl-check agent URL from cluster |
| `monitor-build.ps1` | Poll a single build (`-Job template-test`) |
| `jenkins-api.ps1` | Shared REST helpers (basic auth + crumb) |

## Jobs

Under `jobs/`:

- **`template-test.groovy`** — static cloud template via `agent { label '…' }`
- **`dec-json-test.groovy`** — declarative `slurm { json '…' }` CPU stage

Placeholders like `${SLURM_CLOUD_NAME}` are expanded from `config.env` at configure time.

## Iteration workflow

```powershell
# Rebuild plugin and re-upload only
mvn -ntp -q clean package
pwsh -File scripts/jenkins-instance/deploy.ps1 -SkipBuild

# Re-run tests (skip rebuild/deploy)
pwsh -File scripts/jenkins-instance/run-e2e.ps1 -SkipBuild -SkipDeploy

# Fresh Docker image
pwsh -File scripts/jenkins-instance/deploy.ps1 -Recreate
```

## Troubleshooting

- **Jenkins not reachable:** check `docker ps` on remote host; firewall for port `JENKINS_HTTP_PORT`.
- **Plugin upload fails:** confirm admin password; check `/manage/pluginManager/`.
- **Agent never connects:** verify `JENKINS_AGENT_URL` from a compute node (`curl`); no tunnel needed.
- **JWT errors:** set `SLURM_JWT_TOKEN` explicitly or run `scontrol token` on the cluster.
- **Invalid partition / PartitionTimeLimit:** set `E2E_TEMPLATE_PARTITION` and `E2E_TIME_LIMIT_MINUTES` to match your cluster (`sinfo`, `scontrol show partition`).
- **Static template agent fails (`/opt/java/openjdk/bin/java` missing):** the harness configures Pyxis on the cloud template so agents run in the inbound-agent container image.

## Files on remote host

Deployed to `JENKINS_REMOTE_DIR` (default `/tmp/jenkins-instance` on local disk — avoid NFS home for Docker):

- `docker-compose.yml`, `plugins.txt`
- Persistent volume `jenkins_home` for Jenkins data

**Note:** Home directories on NFS (common on HPC clusters) break `docker compose build`. Use `/tmp/...` or another local path for `JENKINS_REMOTE_DIR`.
