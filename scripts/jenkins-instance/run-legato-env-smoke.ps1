# Configure legato template, trigger env-var smoke job, poll result.
#
# Usage:
#   pwsh -File scripts/jenkins-instance/run-legato-env-smoke.ps1
#   pwsh -File scripts/jenkins-instance/run-legato-env-smoke.ps1 -SkipConfigure

param(
    [switch]$SkipConfigure
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$base = $cfg['JENKINS_URL']
$user = $cfg['JENKINS_ADMIN_USER']
$pass = $cfg['JENKINS_ADMIN_PASSWORD']
$folder = $cfg['LEGATO_FOLDER']
$jobName = $cfg['LEGATO_JOB']
$jobPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName")

Write-Host '=== Legato env-var smoke run ==='
Write-Host "Jenkins: $base"
Write-Host "Job: $jobPath"

if (-not (Test-JenkinsUp -BaseUrl $base)) {
    throw "Jenkins not reachable at $base"
}

$jenkins = if ($pass -and $pass -ne 'unused') {
    Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
} else {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
    $headers = @{}
    $headers[$crumb.crumbRequestField] = $crumb.crumb
    @{ Session = $session; Headers = $headers; BaseUrl = $base.TrimEnd('/') }
}

if (-not $SkipConfigure) {
    & "$PSScriptRoot\configure-legato-template.ps1"
    $jenkins = if ($pass -and $pass -ne 'unused') {
        Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
    } else {
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
        $headers = @{}
        $headers[$crumb.crumbRequestField] = $crumb.crumb
        @{ Session = $session; Headers = $headers; BaseUrl = $base.TrimEnd('/') }
    }
}

Remove-StaleSlurmNodes -Jenkins $jenkins -CloudName $cfg['SLURM_CLOUD_NAME']

Write-Host 'Triggering build...'
$build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
Write-Host "Build #$($build.number) started: $($build.url)"

$final = Wait-JenkinsBuild -Jenkins $jenkins -Cfg $cfg -JobPath $jobPath -BuildNumber $build.number -MaxPolls 240
Write-Host "=== Result: $($final.result) (duration $($final.duration)ms) ==="
if ($final.result -ne 'SUCCESS') {
    $tail = Get-BuildConsoleTail -Jenkins $jenkins -JobPath $jobPath -BuildNumber $build.number -Lines 60
    $tail | ForEach-Object { Write-Host $_ }
    exit 1
}
exit 0
