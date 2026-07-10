# Remove all Slurm cloud agents and list remainder.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$cloud = $c['SLURM_CLOUD_NAME']

Write-Host "=== Before ==="
Invoke-JenkinsScript -Jenkins $j -Groovy "return jenkins.model.Jenkins.get().nodes.findAll{it.name?.contains('$cloud')}*.name.join(', ') ?: 'none'"

Remove-StaleSlurmNodes -Jenkins $j -CloudName $cloud

Write-Host "=== After ==="
Invoke-JenkinsScript -Jenkins $j -Groovy "return jenkins.model.Jenkins.get().nodes.findAll{it.name?.contains('$cloud')}*.name.join(', ') ?: 'none'"
