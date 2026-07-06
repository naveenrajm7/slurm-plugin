param([int]$Build = 2)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$folder = $c['WORKLOAD_FOLDER']
$jobName = $c['WORKLOAD_JOB']
$branch = if ($c['WORKLOAD_GIT_BRANCH']) { $c['WORKLOAD_GIT_BRANCH'] } else { 'develop' }
$jobPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName/$branch")
$text = Invoke-JenkinsApi -Jenkins $j -Uri "$($j.BaseUrl)/$jobPath/$Build/consoleText"
if ($text -is [byte[]]) { $text = [Text.Encoding]::UTF8.GetString($text) }
"$text" -split "`n" | Select-String -Pattern '^\[Pipeline\] (node|stage|error)|ERROR:|Exception|abort|Finished:|runOnHealthy|FORCE_CI|Skipping|doesn.t have label|Waiting for next|online' | Select-Object -First 60 | ForEach-Object { $_.Line }
