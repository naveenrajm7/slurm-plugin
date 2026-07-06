$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$cloudName = $cfg['SLURM_CLOUD_NAME']
$folder = $cfg['E2E_FOLDER']
$jobName = $cfg['E2E_JOB_TEMPLATE_TEST']
$jobPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName")
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

Write-Host "=== Build console tail ($folder/$jobName) ==="
try {
    $build = Invoke-JenkinsApi -Jenkins $jenkins -Uri "$($jenkins.BaseUrl)/job/$jobPath/lastBuild/api/json?tree=number,building,result"
    $txt = (Invoke-JenkinsApi -Jenkins $jenkins -Uri "$($jenkins.BaseUrl)/job/$jobPath/$($build.number)/consoleText")
    if ($txt -is [byte[]]) { $txt = [Text.Encoding]::UTF8.GetString($txt) }
    ($txt -split "`n" | Select-Object -Last 15) | ForEach-Object { Write-Host $_ }
} catch {
    Write-Host "console: $_"
}

Write-Host ''
Write-Host '=== Label match detail (smoke-nogpu template) ==='
$groovy = @"
import io.jenkins.plugins.slurm.*
import hudson.model.Label
def cloud = Jenkins.instance.clouds.find { it.name == '$cloudName' }
def t = cloud.jobTemplates.find { it.name == 'smoke-nogpu' }
def req = Label.parseExpression("(rocmtest || miopen) && nogpu")
def atoms = t.labelAtoms
def sb = new StringBuilder()
sb << "template.label=" << t.label << "\n"
sb << "atomNames=" << atoms.collect { it.name } << " count=" << atoms.size() << "\n"
sb << "req.matches(atoms)=" << req.matches(atoms) << "\n"
sb << "canTake(Label)=" << t.canTake(req) << "\n"
return sb.toString()
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
