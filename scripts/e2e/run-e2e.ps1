# Run Slurm plugin end-to-end tests (local hpi:run + live cluster).
#
# Setup (once):
#   cp scripts/e2e/config.env.example scripts/e2e/config.env   # fill in; never commit
#   mvn hpi:run
#   wsl -e bash scripts/e2e/start-tunnel.sh   # separate terminal
#
# Usage:
#   pwsh -File scripts/e2e/run-e2e.ps1
#   pwsh -File scripts/e2e/run-e2e.ps1 -SkipBuild -CleanStaleAgents

param(
    [string[]]$Jobs = @(),
    [switch]$SkipBuild,
    [switch]$SkipTunnelCheck,
    [switch]$CleanStaleAgents
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-E2EConfig
Assert-E2EConfig -Cfg $cfg

$base = $cfg['JENKINS_URL']
$folder = $cfg['E2E_FOLDER']
$cloud = $cfg['SLURM_CLOUD_NAME']

if ($Jobs.Count -eq 0) {
    $Jobs = @(
        $cfg['E2E_JOB_TEMPLATE_TEST'],
        $cfg['E2E_JOB_DECLARATIVE_JSON']
    ) | Where-Object { $_ }
}

Write-Host "=== Slurm plugin e2e ==="
Write-Host "Jenkins: $base"

if (-not (Test-JenkinsUp -BaseUrl $base)) {
    throw "Jenkins not reachable at $base. Start with: mvn -ntp hpi:run"
}

if (-not $SkipTunnelCheck -and -not (Test-AgentTunnel -Cfg $cfg)) {
    throw "Agent tunnel not reachable. Start: wsl -e bash $PSScriptRoot/start-tunnel.sh"
}
Write-Host "Tunnel: OK"

if (-not $SkipBuild) {
    Write-Host "=== mvn clean package ==="
    Push-Location (Resolve-Path "$PSScriptRoot\..\..")
    try {
        & mvn -ntp -q clean package
        if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
    } finally { Pop-Location }
    Write-Host "Build: OK"
}

$jenkins = Get-JenkinsSession -BaseUrl $base

if ($CleanStaleAgents) {
    Write-Host "Removing stale Slurm agent nodes..."
    Remove-StaleSlurmNodes -Jenkins $jenkins -CloudName $cloud
}

if ($cfg['E2E_TEMPLATE_LABEL'] -and $cfg['E2E_TEMPLATE_WORKDIR']) {
    Set-TemplateWorkdir -Jenkins $jenkins -Label $cfg['E2E_TEMPLATE_LABEL'] `
        -Workdir $cfg['E2E_TEMPLATE_WORKDIR'] | Out-Null
}

$wslE2e = (wsl -e wslpath -a $PSScriptRoot).Trim()
wsl -e bash -lc "cd '$wslE2e' && bash prepare-slurm.sh" 2>$null | Out-Null

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
    Write-Host "All e2e jobs passed."
    exit 0
}
$failed | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
exit 1
