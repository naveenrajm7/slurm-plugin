# Configure Slurm cloud, credentials, and e2e jobs on the dedicated Jenkins instance.
#
# Usage:
#   pwsh -File scripts/jenkins-instance/configure.ps1

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
Assert-InstanceConfig -Cfg $cfg -ExtraRequired @('E2E_CPU_CONTAINER_IMAGE')

$base = $cfg['JENKINS_URL']
$user = $cfg['JENKINS_ADMIN_USER']
$pass = $cfg['JENKINS_ADMIN_PASSWORD']

Write-Host "=== Configure Jenkins instance ==="
Write-Host "Jenkins: $base"
Write-Host "Agent URL (Slurm cloud): $($cfg['JENKINS_AGENT_URL'])"

if (-not (Test-JenkinsUp -BaseUrl $base)) {
    throw "Jenkins not reachable at $base. Run deploy.ps1 first."
}

$jenkins = Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
if (-not (Test-SlurmPluginLoaded -Jenkins $jenkins)) {
    throw 'Slurm plugin not loaded. Run deploy.ps1 to build and install the .hpi.'
}

$wslDir = (wsl -e wslpath -a $PSScriptRoot).Trim()
wsl -e bash -lc "cd '$wslDir' && bash prepare-slurm.sh" 2>$null | Out-Null

Write-Host "Applying cloud, credentials, and jobs via Script Console..."
Invoke-ConfigureInstance -Jenkins $jenkins -Cfg $cfg
Write-Host "Configuration complete."
