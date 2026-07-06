# Add production-style Slurm job templates to an existing cloud (native agent, no Pyxis).
#
# Usage:
#   pwsh -File scripts/jenkins-instance/configure-workload-templates.ps1

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

Write-Host "=== Configure workload Slurm templates ==="
Write-Host "Cloud: $($cfg['SLURM_CLOUD_NAME'])"

if (-not (Test-SlurmPluginLoaded -Jenkins $jenkins)) {
    throw 'Slurm plugin not loaded. Run deploy.ps1 first.'
}

$groovyPath = Join-Path $PSScriptRoot 'configure-workload-templates.groovy'
$groovy = Expand-JobTemplate -TemplatePath $groovyPath -Cfg $cfg
$result = Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
if ("$result" -notmatch 'workload-templates-ok') { throw "Template configure failed: $result" }
Write-Host $result
