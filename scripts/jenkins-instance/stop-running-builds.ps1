$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$cfg = Get-InstanceConfig
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg
$instanceE2e = if ($cfg['INSTANCE_E2E_FOLDER']) { $cfg['INSTANCE_E2E_FOLDER'] } else { 'slurm-instance-e2e' }
$ckFolder = if ($cfg['CK_SMOKE_FOLDER']) { $cfg['CK_SMOKE_FOLDER'] } else { 'slurm-ck-smoke' }
$ckJob = if ($cfg['CK_SMOKE_JOB']) { $cfg['CK_SMOKE_JOB'] } else { 'ck-smoke' }
$paths = @(
    "$instanceE2e/template-test",
    "$instanceE2e/dec-json-test",
    "$($cfg['E2E_FOLDER'])/$($cfg['E2E_JOB_TEMPLATE_TEST'])",
    "$($cfg['LEGATO_FOLDER'])/$($cfg['LEGATO_JOB'])",
    "$ckFolder/$ckJob"
)
foreach ($path in $paths) {
    $groovy = "def item = jenkins.model.Jenkins.instance.getItemByFullName('$path'); if (item) { item.builds.findAll { it.isBuilding() }.each { it.doStop() } }"
    Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy | Out-Null
}
Write-Host 'Stopped running builds'
