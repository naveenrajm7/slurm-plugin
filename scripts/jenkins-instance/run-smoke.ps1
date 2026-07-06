# Build (optional), deploy plugin (optional), configure smoke, trigger build, poll Slurm queue.
#
# Usage:
#   pwsh -File scripts/jenkins-instance/run-smoke.ps1
#   pwsh -File scripts/jenkins-instance/run-smoke.ps1 -SkipBuild -SkipDeploy

param(
    [switch]$SkipBuild,
    [switch]$SkipDeploy,
    [switch]$SkipConfigure
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$base = $cfg['JENKINS_URL']
$user = $cfg['JENKINS_ADMIN_USER']
$pass = $cfg['JENKINS_ADMIN_PASSWORD']
$folder = $cfg['E2E_FOLDER']
$jobName = $cfg['E2E_JOB_TEMPLATE_TEST']
$jobPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName")

Write-Host "=== Label expression smoke run ==="
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
    if (-not (Test-JenkinsUp -BaseUrl $base)) {
        & "$PSScriptRoot\deploy.ps1" -SkipBuild
    } else {
        $probe = $null
        try {
            if ($pass -and $pass -ne 'unused') {
                $probe = Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
            } else {
                $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
                $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
                $h = @{}; $h[$crumb.crumbRequestField] = $crumb.crumb
                $probe = @{ Session = $session; Headers = $h; BaseUrl = $base.TrimEnd('/') }
            }
        } catch {}
        if (-not $probe -or -not (Test-SlurmPluginLoaded -Jenkins $probe)) {
            & "$PSScriptRoot\deploy.ps1" -SkipBuild
        }
    }
}

if (-not (Test-JenkinsUp -BaseUrl $base)) {
    throw "Jenkins not reachable at $base"
}

$jenkins = if ($pass -and $pass -ne 'unused') {
    Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
} else {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
    $headers = @{}; $headers[$crumb.crumbRequestField] = $crumb.crumb
    @{ Session = $session; Headers = $headers; BaseUrl = $base.TrimEnd('/') }
}

if (-not $SkipConfigure) {
    & "$PSScriptRoot\configure-smoke.ps1"
    $jenkins = if ($pass -and $pass -ne 'unused') {
        Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
    } else {
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
        $headers = @{}; $headers[$crumb.crumbRequestField] = $crumb.crumb
        @{ Session = $session; Headers = $headers; BaseUrl = $base.TrimEnd('/') }
    }
}

Remove-StaleSlurmNodes -Jenkins $jenkins -CloudName $cfg['SLURM_CLOUD_NAME']

Write-Host "Triggering build..."
$build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
Write-Host "Build #$($build.number) started: $($build.url)"

$final = Wait-JenkinsBuild -Jenkins $jenkins -Cfg $cfg -JobPath $jobPath -BuildNumber $build.number -MaxPolls 240
Write-Host "=== Result: $($final.result) (duration $($final.duration)ms) ==="
if ($final.result -ne 'SUCCESS') {
    $tail = Get-BuildConsoleTail -Jenkins $jenkins -JobPath $jobPath -BuildNumber $build.number -Lines 40
    $tail | ForEach-Object { Write-Host $_ }
    exit 1
}
exit 0
