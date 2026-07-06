# Monitor a Jenkins build with Slurm queue snapshots.
# Usage: pwsh -File scripts/e2e/monitor-build.ps1 -Job template-test -Build 1

param(
    [Parameter(Mandatory)]
    [string]$Job,
    [int]$Build = 0,
    [int]$PollSeconds = 5,
    [int]$MaxPolls = 180
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-E2EConfig
Assert-E2EConfig -Cfg $cfg

$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL']
$folder = $cfg['E2E_FOLDER']
$jobPath = ConvertTo-JobPath -JobFullName "$folder/$Job"

if ($Build -eq 0) {
    $Build = (Invoke-RestMethod -Uri "$($jenkins.BaseUrl)/$jobPath/lastBuild/api/json?tree=number").number
}

Wait-JenkinsBuild -Jenkins $jenkins -Cfg $cfg -JobPath $jobPath -BuildNumber $Build `
    -PollSeconds $PollSeconds -MaxPolls $MaxPolls
