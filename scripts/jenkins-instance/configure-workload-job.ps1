# Create rocm-libraries-folder / Composable Kernel job and ck shared library on test Jenkins.
#
# Usage:
#   pwsh -File scripts/jenkins-instance/configure-workload-job.ps1

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

Write-Host "=== Configure workload pipeline job ==="
Write-Host "Folder: $($cfg['WORKLOAD_FOLDER'])"
Write-Host "Job: $($cfg['WORKLOAD_JOB'])"

$groovyPath = Join-Path $PSScriptRoot 'configure-workload-job.groovy'
$groovy = Expand-JobTemplate -TemplatePath $groovyPath -Cfg $cfg
$result = Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
if ("$result" -notmatch 'workload-multibranch-ok') { throw "Job configure failed: $result" }
Write-Host $result
