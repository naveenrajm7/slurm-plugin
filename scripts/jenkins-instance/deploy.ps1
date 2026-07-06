# Deploy Docker Jenkins on the remote host (SSH).
#
# Usage:
#   pwsh -File scripts/jenkins-instance/deploy.ps1
#   pwsh -File scripts/jenkins-instance/deploy.ps1 -SkipBuild

param(
    [switch]$SkipBuild,
    [switch]$Recreate
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
Assert-InstanceConfig -Cfg $cfg

$remoteDir = $cfg['JENKINS_REMOTE_DIR']
if ([string]::IsNullOrWhiteSpace($remoteDir)) { $remoteDir = '~/jenkins-instance' }
$port = if ($cfg['JENKINS_HTTP_PORT']) { $cfg['JENKINS_HTTP_PORT'] } else { '8282' }
$adminUser = $cfg['JENKINS_ADMIN_USER']
$adminPass = $cfg['JENKINS_ADMIN_PASSWORD']

Write-Host "=== Deploy Jenkins instance ==="
Write-Host "Host: $($cfg['JENKINS_SSH_HOST'])"
Write-Host "Remote dir: $remoteDir"
Write-Host "Port: $port"

Invoke-InstanceSsh -Cfg $cfg -RemoteCommand "mkdir -p $remoteDir"

$files = @(
    'docker-compose.yml',
    'plugins.txt'
)
foreach ($name in $files) {
    $local = Join-Path $PSScriptRoot $name
    Invoke-InstanceScp -Cfg $cfg -LocalPath $local -RemotePath "${remoteDir}/${name}"
}

$recreateFlag = if ($Recreate) { ' --force-recreate' } else { '' }
$remoteCmd = "cd $remoteDir && export JENKINS_HTTP_PORT=$port JENKINS_ADMIN_USER='$adminUser' JENKINS_ADMIN_PASSWORD='$adminPass' && docker compose pull && docker compose up -d$recreateFlag"

Write-Host "Starting Docker Compose on remote..."
Invoke-InstanceSsh -Cfg $cfg -RemoteCommand $remoteCmd

Wait-JenkinsReady -BaseUrl $cfg['JENKINS_URL'] -User $adminUser -Password $adminPass
Write-Host "Jenkins is up at $($cfg['JENKINS_URL'])"

Write-Host "Installing base plugins via jenkins-plugin-cli..."
$pluginCmd = "cd $remoteDir && docker cp plugins.txt slurm-jenkins:/tmp/plugins.txt && docker exec -u root slurm-jenkins jenkins-plugin-cli --plugin-file /tmp/plugins.txt && docker restart slurm-jenkins"
Invoke-InstanceSsh -Cfg $cfg -RemoteCommand $pluginCmd
Wait-JenkinsReady -BaseUrl $cfg['JENKINS_URL'] -User $adminUser -Password $adminPass

if (-not $SkipBuild) {
    Write-Host "=== mvn clean package ==="
    Push-Location (Resolve-Path "$PSScriptRoot\..\..")
    try {
        & mvn -ntp -q package "-Dmaven.clean.skip=true"
        if ($LASTEXITCODE -ne 0) { throw 'mvn package failed' }
    } finally { Pop-Location }
}

$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL'] -User $adminUser -Password $adminPass
$hpi = Resolve-Path "$PSScriptRoot\..\..\target\slurm.hpi"
Write-Host "Installing plugin: $hpi"
Install-JenkinsPluginHpi -Jenkins $jenkins -Cfg $cfg -HpiPath $hpi

$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL'] -User $adminUser -Password $adminPass
if (-not (Test-SlurmPluginLoaded -Jenkins $jenkins)) {
    throw 'Slurm plugin did not load after upload'
}
Write-Host "Plugin installed."

Write-Host "Run configure.ps1 next (or run-e2e.ps1 -Configure)."
