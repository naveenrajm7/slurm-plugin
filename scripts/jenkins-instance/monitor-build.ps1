# Monitor a Jenkins build on the dedicated instance.
# Usage: pwsh -File scripts/jenkins-instance/monitor-build.ps1 -Job template-test -Build 1

param(
    [Parameter(Mandatory)]
    [string]$Job,
    [int]$Build = 0,
    [int]$PollSeconds = 5,
    [int]$MaxPolls = 180
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
Assert-InstanceConfig -Cfg $cfg

$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL'] `
    -User $cfg['JENKINS_ADMIN_USER'] -Password $cfg['JENKINS_ADMIN_PASSWORD']
$folder = $cfg['E2E_FOLDER']
$jobPath = ConvertTo-JobPath -JobFullName "$folder/$Job"

if ($Build -eq 0) {
    $Build = (Invoke-RestMethod -Uri "$($jenkins.BaseUrl)/$jobPath/lastBuild/api/json?tree=number" `
        -Headers $jenkins.Headers -WebSession $jenkins.Session).number
}

Wait-JenkinsBuild -Jenkins $jenkins -Cfg $cfg -JobPath $jobPath -BuildNumber $Build `
    -PollSeconds $PollSeconds -MaxPolls $MaxPolls
