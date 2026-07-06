# Run Slurm plugin tests against the dedicated Docker Jenkins instance.
#
# Setup (once):
#   cp scripts/jenkins-instance/config.env.example scripts/jenkins-instance/config.env
#   pwsh -File scripts/jenkins-instance/deploy.ps1
#   pwsh -File scripts/jenkins-instance/configure.ps1
#
# Usage:
#   pwsh -File scripts/jenkins-instance/run-e2e.ps1
#   pwsh -File scripts/jenkins-instance/run-e2e.ps1 -SkipBuild -Configure

param(
    [string[]]$Jobs = @(),
    [switch]$SkipBuild,
    [switch]$SkipDeploy,
    [switch]$Configure,
    [switch]$CleanStaleAgents
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
Assert-InstanceConfig -Cfg $cfg

$base = $cfg['JENKINS_URL']
$user = $cfg['JENKINS_ADMIN_USER']
$pass = $cfg['JENKINS_ADMIN_PASSWORD']
$folder = $cfg['E2E_FOLDER']
$cloud = $cfg['SLURM_CLOUD_NAME']

if ($Jobs.Count -eq 0) {
    $Jobs = @(
        $cfg['E2E_JOB_TEMPLATE_TEST'],
        $cfg['E2E_JOB_DECLARATIVE_JSON']
    ) | Where-Object { $_ }
}

Write-Host "=== Slurm plugin instance e2e ==="
Write-Host "Jenkins: $base"
Write-Host "Agent URL: $($cfg['JENKINS_AGENT_URL'])"

if (-not $SkipBuild) {
    Write-Host "=== mvn clean package ==="
    Push-Location (Resolve-Path "$PSScriptRoot\..\..")
    try {
        & mvn -ntp -q package "-Dmaven.clean.skip=true"
        if ($LASTEXITCODE -ne 0) { throw 'mvn package failed' }
    } finally { Pop-Location }
    Write-Host "Build: OK"
}

if (-not $SkipDeploy) {
    $jenkinsProbe = $null
    if (Test-JenkinsUp -BaseUrl $base) {
        try { $jenkinsProbe = Get-JenkinsSession -BaseUrl $base -User $user -Password $pass } catch {}
    }
    if (-not $jenkinsProbe -or -not (Test-SlurmPluginLoaded -Jenkins $jenkinsProbe)) {
        Write-Host "Deploying / updating plugin on instance..."
        & "$PSScriptRoot\deploy.ps1" -SkipBuild
    }
}

if (-not (Test-JenkinsUp -BaseUrl $base)) {
    throw "Jenkins not reachable at $base. Run deploy.ps1"
}

$jenkins = Get-JenkinsSession -BaseUrl $base -User $user -Password $pass

if ($Configure) {
    & "$PSScriptRoot\configure.ps1"
    $jenkins = Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
}

if (-not (Test-SlurmPluginLoaded -Jenkins $jenkins)) {
    throw 'Slurm plugin not loaded'
}

if ($CleanStaleAgents) {
    Write-Host "Removing stale Slurm agent nodes..."
    Remove-StaleSlurmNodes -Jenkins $jenkins -CloudName $cloud
}

$wslDir = (wsl -e wslpath -a $PSScriptRoot).Trim()
wsl -e bash -lc "cd '$wslDir' && bash prepare-slurm.sh" 2>$null | Out-Null

$failed = @()
foreach ($jobName in $Jobs) {
    if ([string]::IsNullOrWhiteSpace($jobName)) { continue }
    $jobPath = ConvertTo-JobPath -JobFullName "$folder/$jobName"
    Write-Host "`n=== Trigger $folder/$jobName ==="
    $build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
    Write-Host "Build #$($build.number)"
    $final = Wait-JenkinsBuild -Jenkins $jenkins -Cfg $cfg -JobPath $jobPath -BuildNumber $build.number
    if ($final.result -ne 'SUCCESS') {
        $failed += "$folder/$jobName #$($build.number) => $($final.result)"
        Write-Host "FAILED: $($final.result)" -ForegroundColor Red
    } else {
        Write-Host "PASSED" -ForegroundColor Green
    }
}

Write-Host "`n=== Summary ==="
if ($failed.Count -eq 0) {
    Write-Host "All instance e2e jobs passed."
    exit 0
}
$failed | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
exit 1
