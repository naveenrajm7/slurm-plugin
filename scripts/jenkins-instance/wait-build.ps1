param([int]$Build = 9, [int]$MaxPolls = 40)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$folder = $c['WORKLOAD_FOLDER']
$jobName = $c['WORKLOAD_JOB']
$branch = if ($c['WORKLOAD_GIT_BRANCH']) { $c['WORKLOAD_GIT_BRANCH'] } else { 'develop' }
$jobPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName/$branch")

for ($i = 0; $i -lt $MaxPolls; $i++) {
    $s = Invoke-JenkinsApi -Jenkins $j -Uri "$($j.BaseUrl)/$jobPath/$Build/api/json?tree=building,result,duration"
    Write-Host "poll $i building=$($s.building) result=$($s.result) duration=$($s.duration)"
    if (-not $s.building) { break }
    Start-Sleep -Seconds 15
}

Write-Host "`n=== Key lines ==="
& "$PSScriptRoot\grep-build-keylines.ps1" -Build $Build
