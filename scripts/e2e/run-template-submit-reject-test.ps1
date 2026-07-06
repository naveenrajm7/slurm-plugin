# Configure cloud template + run static-label submit-reject test.
# Usage: powershell -File scripts/e2e/run-template-submit-reject-test.ps1

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-E2EConfig
$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL']
$folder = $cfg['E2E_FOLDER']
$cloudName = $cfg['SLURM_CLOUD_NAME']
$jobName = 'template-submit-reject-test'
$jobPath = ConvertTo-JobPath -JobFullName "$folder/$jobName"
$templateLabel = 'bad-gfx-template'
$partition = if ($cfg['E2E_PARTITION']) { $cfg['E2E_PARTITION'] } else { 'jenkins-e2e' }
$constraint = if ($cfg['E2E_FEATURE']) { $cfg['E2E_FEATURE'] } else { 'jenkins-e2e' }
$workdir = if ($cfg['E2E_TEMPLATE_WORKDIR']) { $cfg['E2E_TEMPLATE_WORKDIR'] } else { '/tmp/jenkins' }

Write-Host "=== Configure cloud template '$templateLabel' on $cloudName ==="
$setup = @"
import io.jenkins.plugins.slurm.SlurmCloud
import io.jenkins.plugins.slurm.SlurmJobTemplate

def j = jenkins.model.Jenkins.get()
def cloud = j.clouds.find { it.name == '$cloudName' } as SlurmCloud
if (cloud == null) { return 'ERROR: cloud not found' }

def templates = cloud.jobTemplates
def t = templates.find { it.label == '$templateLabel' }
if (t == null) {
  t = new SlurmJobTemplate('bad-gfx-template', '$templateLabel')
  templates.add(t)
}
t.setPartition('$partition')
t.setConstraints('$constraint')
t.setTresPerJob('gres/gpu:gfx1030:1')
t.setCpusPerTask(2)
t.setMemoryPerNode(4096L)
t.setTimeLimit(60)
t.setCurrentWorkingDirectory('$workdir')
t.setInstanceCap(1)
j.save()
return "template label=${t.label} partition=${t.partition} tres=${t.tresPerJob} constraints=${t.constraints}"
"@
Write-Host (Invoke-JenkinsScript -Jenkins $jenkins -Groovy $setup)

$pipeline = Get-Content -Raw -LiteralPath "$PSScriptRoot\template-submit-reject-test.groovy"
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pipeline))

Write-Host "=== Create/update job $folder/$jobName ==="
$groovyJob = @"
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import java.util.Base64

def j = jenkins.model.Jenkins.get()
def folder = j.getItemByFullName('$folder')
if (folder == null) { return 'ERROR: folder not found' }
def job = folder.getItem('$jobName')
if (job == null) {
  job = folder.createProject(WorkflowJob.class, '$jobName')
}
def script = new String(Base64.decoder.decode('$b64'), 'UTF-8')
job.setDefinition(new CpsFlowDefinition(script, true))
job.save()
return job.fullName
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovyJob | Out-Null

Write-Host "=== Trigger build (static template via agent { label }) ==="
$build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
$buildNum = $build.number
Write-Host "Build #$buildNum"

for ($i = 0; $i -lt 30; $i++) {
    $b = Get-BuildStatus -Jenkins $jenkins -JobPath $jobPath -BuildNumber $buildNum
    if (-not $b.building) {
        $full = (Invoke-WebRequest -Uri "$($jenkins.BaseUrl)/$jobPath/$buildNum/consoleText" -UseBasicParsing).Content
        $hasSubmitFail = $full -match '\[Slurm\] Job submission failed:'
        $hasPending = $full -match '\[Slurm\].*PENDING'
        Write-Host "`n=== Result: $($b.result) ==="
        Write-Host "  [Slurm] Job submission failed: $hasSubmitFail"
        Write-Host "  [Slurm] PENDING (should be false): $hasPending"
        ($full -split "`n" | Select-String -Pattern '\[Slurm\]|ERROR:|Queue task|Finished:' | ForEach-Object { $_.Line }) | ForEach-Object { Write-Host "  $_" }
        if ($hasSubmitFail -and -not $hasPending -and $b.result -eq 'ABORTED') {
            Write-Host "PASS: static template submit-reject verified" -ForegroundColor Green
            exit 0
        }
        Write-Host "FAIL: expected ABORTED with [Slurm] Job submission failed" -ForegroundColor Red
        exit 1
    }
    Start-Sleep -Seconds 5
}
throw "Timeout waiting for build #$buildNum"
