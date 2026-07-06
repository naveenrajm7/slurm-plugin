# Case 1: Slurm rejects job at submit (jenkins-e2e + gfx1030).
# Usage: powershell -File scripts/e2e/run-submit-reject-gfx1030-test.ps1

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-E2EConfig
$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL']
$folder = $cfg['E2E_FOLDER']
$jobName = 'submit-reject-gfx1030-test'
$jobPath = ConvertTo-JobPath -JobFullName "$folder/$jobName"
$cloud = $cfg['SLURM_CLOUD_NAME']

$pipeline = Get-Content -Raw -LiteralPath "$PSScriptRoot\submit-reject-gfx1030-test.groovy"
$pipeline = $pipeline.Replace("cloud 'my-slurm-cloud'", "cloud '$cloud'")
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pipeline))

Write-Host "=== Create/update job $folder/$jobName ==="
$groovyJob = @"
import com.cloudbees.hudson.plugins.folder.Folder
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

Write-Host "=== Trigger build (expect submit rejection) ==="
$build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
$buildNum = $build.number
Write-Host "Build #$buildNum"

for ($i = 0; $i -lt 30; $i++) {
    $b = Get-BuildStatus -Jenkins $jenkins -JobPath $jobPath -BuildNumber $buildNum
    $slurm = Get-SlurmQueue -Cfg $cfg
    $tail = Get-BuildConsoleTail -Jenkins $jenkins -JobPath $jobPath -BuildNumber $buildNum -Lines 30
    Write-Host "`n===== poll $i $(Get-Date -Format HH:mm:ss) #$($b.number) building=$($b.building) result=$($b.result) ====="
    if ($slurm) { $slurm -split "`n" | ForEach-Object { Write-Host "  slurm: $_" } }
    else { Write-Host '  slurm: (none)' }
    $tail | ForEach-Object { Write-Host "  $_" }

    if (-not $b.building) {
        $full = (Invoke-WebRequest -Uri "$($jenkins.BaseUrl)/$jobPath/$buildNum/consoleText" -UseBasicParsing).Content
        $hasSubmitFail = $full -match '\[Slurm\] Job submission failed:'
        $hasPending = $full -match '\[Slurm\].*PENDING'
        Write-Host "`n=== Verification ==="
        Write-Host "  result=$($b.result)"
        Write-Host "  [Slurm] Job submission failed: $hasSubmitFail"
        Write-Host "  [Slurm] PENDING (should be false): $hasPending"
        Write-Host "  slurm queue empty: $([string]::IsNullOrWhiteSpace($slurm))"
        if ($hasSubmitFail -and -not $hasPending -and $b.result -eq 'ABORTED') {
            Write-Host "PASS: submit-reject case verified" -ForegroundColor Green
            exit 0
        }
        Write-Host "FAIL: expected ABORTED with [Slurm] Job submission failed and no PENDING" -ForegroundColor Red
        exit 1
    }
    Start-Sleep -Seconds 5
}

throw "Timeout waiting for build #$buildNum"
