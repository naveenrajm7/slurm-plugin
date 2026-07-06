$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$g = Expand-JobTemplate -TemplatePath "$PSScriptRoot\probe-multibranch.groovy" -Cfg $c
Invoke-JenkinsScript -Jenkins $j -Groovy $g
