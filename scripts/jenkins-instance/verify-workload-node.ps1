# Phase 2: verify a Slurm compute node can reach Jenkins and run java (post-smoke node name optional).
#
# Usage:
#   pwsh -File scripts/jenkins-instance/verify-workload-node.ps1
#   pwsh -File scripts/jenkins-instance/verify-workload-node.ps1 -Node ppac-cyxtera-cx76-3.adc.amd.com

param([string]$Node)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
if (-not $Node) {
    $Node = if ($cfg['WORKLOAD_VERIFY_NODE']) { $cfg['WORKLOAD_VERIFY_NODE'] } else { 'ppac-cyxtera-cx76-3.adc.amd.com' }
}

Write-Host "=== Phase 2: compute node prep check ==="
Write-Host "Node: $Node"
Write-Host "Agent URL: $($cfg['JENKINS_AGENT_URL'])"

wsl -e bash -lc "cd '$(wsl -e wslpath -a $PSScriptRoot)' && bash check-compute-node.sh '$Node'"

Write-Host "=== docker / rocm on node (via login hop) ==="
$sshHost = $cfg['SLURM_SSH_HOST']
$opts = if ($cfg['SLURM_SSH_OPTS']) { $cfg['SLURM_SSH_OPTS'] } else { '-o BatchMode=yes -o ConnectTimeout=10' }
$cmd = @"
ssh -o ConnectTimeout=15 '$Node' 'docker info >/dev/null 2>&1 && echo docker:ok || echo docker:missing; test -e /dev/kfd && echo kfd:ok || echo kfd:missing; rocminfo 2>/dev/null | head -3 || echo rocminfo:missing'
"@
wsl -e bash -lc "ssh $opts '${sshHost}' '$cmd'"
