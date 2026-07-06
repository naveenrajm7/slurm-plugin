# E2e smoke: verify build console logs compute node placement when agent connects.
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-E2EConfig
Assert-E2EConfig -Cfg $cfg

$base = $cfg['JENKINS_URL']
$folder = $cfg['E2E_FOLDER']
$jobName = if ($cfg['E2E_JOB_TEMPLATE_TEST']) { $cfg['E2E_JOB_TEMPLATE_TEST'] } else { throw 'Set E2E_JOB_TEMPLATE_TEST in config.env' }

if (-not (Test-JenkinsUp -BaseUrl $base)) {
    throw "Jenkins not reachable at $base"
}
if (-not (Test-AgentTunnel -Cfg $cfg)) {
    throw "Agent tunnel not reachable"
}

if (-not $SkipBuild) {
    Push-Location (Resolve-Path "$PSScriptRoot\..\..")
    try {
        & mvn -ntp -q package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'mvn package failed' }
    } finally { Pop-Location }
}

$jenkins = Get-JenkinsSession -BaseUrl $base
$jobPath = ConvertTo-JobPath -JobFullName "$folder/$jobName"

Write-Host "=== Compute node visibility e2e: $folder/$jobName ==="
$build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
$final = Wait-JenkinsBuild -Jenkins $jenkins -Cfg $cfg -JobPath $jobPath -BuildNumber $build.number

if ($final.result -ne 'SUCCESS') {
    throw "Build failed: $($final.result)"
}

$console = (Invoke-WebRequest -Uri "$base/$jobPath/$($build.number)/consoleText" -UseBasicParsing).Content
if ($console -notmatch '\[Slurm\] Slurm job \d+ on node\(s\) ') {
    throw "Build console missing compute-node placement line (expected '[Slurm] Slurm job <id> on node(s) <host>')"
}

Write-Host "PASSED: build console includes compute node placement"
exit 0
