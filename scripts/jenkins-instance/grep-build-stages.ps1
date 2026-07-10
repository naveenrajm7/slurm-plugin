param([int]$Build = 9)
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
$patterns = 'stage|Running on|Preflight|SHOULD_RUN_CI|Static checks|Docker|amdgpu|exit code|ERROR:|hostname|Building Docker|mirror clone|checkout|Finished'
"$text" -split "`n" | Select-String -Pattern $patterns | Select-Object -First 80 | ForEach-Object { $_.Line }
