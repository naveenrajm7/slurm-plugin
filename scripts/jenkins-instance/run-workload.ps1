# Phase 5b: deploy plugin, configure workload templates + CK multibranch job, trigger minimal build.
#
# Usage:
#   pwsh -File scripts/jenkins-instance/run-workload.ps1
#   pwsh -File scripts/jenkins-instance/run-workload.ps1 -SkipBuild -SkipDeploy

param(
    [switch]$SkipBuild,
    [switch]$SkipDeploy,
    [switch]$SkipConfigure
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$base = $cfg['JENKINS_URL']
$folder = $cfg['WORKLOAD_FOLDER']
$jobName = $cfg['WORKLOAD_JOB']
$branch = $cfg['WORKLOAD_GIT_BRANCH']
if ([string]::IsNullOrWhiteSpace($branch)) { $branch = 'develop' }
$jobPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName/$branch")
$cloudName = $cfg['SLURM_CLOUD_NAME']

Write-Host "=== Production workload validation (Phase 5b) ==="
Write-Host "Jenkins: $base"
Write-Host "Job: $jobPath"

if (-not $SkipBuild) {
    Write-Host "=== mvn package ==="
    Push-Location (Resolve-Path "$PSScriptRoot\..\..")
    try {
        & mvn -ntp -q package "-Dmaven.clean.skip=true"
        if ($LASTEXITCODE -ne 0) { throw 'mvn package failed' }
    } finally { Pop-Location }
}

if (-not $SkipDeploy) {
    & "$PSScriptRoot\deploy.ps1" -SkipBuild
}

$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

if (-not $SkipConfigure) {
    & "$PSScriptRoot\configure-workload-templates.ps1"
    & "$PSScriptRoot\configure-workload-job.ps1"
    $jenkins = Connect-JenkinsScriptConsole -Cfg $cfg
}

Remove-StaleSlurmNodes -Jenkins $jenkins -CloudName $cloudName

Write-Host "Waiting for multibranch index (develop job)..."
$mbPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName")
for ($i = 0; $i -lt 120; $i++) {
    try {
        $null = Invoke-JenkinsApi -Jenkins $jenkins -Uri "$($jenkins.BaseUrl)/$jobPath/api/json?tree=name"
        Write-Host "Branch job ready: $branch"
        break
    } catch {
        if ($i -eq 119) { throw "Timed out waiting for branch job $folder/$jobName/$branch" }
        Start-Sleep -Seconds 5
    }
}

$params = @{
    FORCE_CI                     = 'true'
    BUILD_DOCKER                 = 'false'
    BUILD_GFX942                 = if ($cfg['WORKLOAD_BUILD_GFX942'] -eq 'true') { 'true' } else { 'false' }
    BUILD_GFX950                 = if ($cfg['WORKLOAD_BUILD_GFX950'] -eq 'true') { 'true' } else { 'false' }
    BUILD_GFX90A                 = 'false'
    BUILD_GFX11                  = 'false'
    BUILD_GFX12                  = 'false'
    BUILD_GFX103                 = 'false'
    BUILD_GFX101                 = 'false'
    BUILD_GFX908                 = 'false'
    RUN_TILE_ENGINE_BASIC_TESTS  = 'false'
    RUN_CK_TILE_FMHA_TESTS       = 'false'
    USE_SCCACHE                  = 'false'
    RUN_PERFORMANCE_TESTS        = 'false'
    BUILD_PACKAGES               = 'false'
    DISABLE_SMART_BUILD          = 'true'
}

Write-Host "Triggering build..."
$build = $null
try {
    $build = Start-JenkinsParameterizedBuild -Jenkins $jenkins -JobPath $jobPath -Parameters $params
} catch {
    Write-Host "Parameterized build unavailable - triggering plain build"
    $build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
}
Write-Host "Build #$($build.number) started: $($build.url)"

$final = Wait-JenkinsBuild -Jenkins $jenkins -Cfg $cfg -JobPath $jobPath -BuildNumber $build.number -MaxPolls 360
Write-Host "=== Result: $($final.result) (duration $($final.duration)ms) ==="
if ($final.result -ne 'SUCCESS') {
    $tail = Get-BuildConsoleTail -Jenkins $jenkins -JobPath $jobPath -BuildNumber $build.number -Lines 60
    $tail | ForEach-Object { Write-Host $_ }
    exit 1
}
exit 0
