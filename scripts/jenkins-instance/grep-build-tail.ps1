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
$patterns = 'Slurm|agent|launch|JNLP|websocket|cancel|timeout|offline|connect|ERROR|Exception|doesn.t have|Preflight|Running on|Node attempt|Finished'
"$text" -split "`n" | Select-String -Pattern $patterns | Select-Object -Last 50 | ForEach-Object { $_.Line }
