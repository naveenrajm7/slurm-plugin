# Trigger gfx1030 pending test, watch console, optionally scancel Slurm job.
# Usage:
#   powershell -File scripts/e2e/run-pending-gfx1030-test.ps1
#   powershell -File scripts/e2e/run-pending-gfx1030-test.ps1 -CancelAfterSeconds 90

param(
    [int]$CancelAfterSeconds = 0,
    [int]$MaxPolls = 60,
    [int]$PollSeconds = 10
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-E2EConfig
$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL']
$folder = $cfg['E2E_FOLDER']
$jobName = 'pending-gfx1030-test'
$jobPath = ConvertTo-JobPath -JobFullName "$folder/$jobName"
$cloud = $cfg['SLURM_CLOUD_NAME']

$pipeline = Get-Content -Raw -LiteralPath "$PSScriptRoot\pending-gfx1030-test.groovy"
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

Write-Host "=== Trigger build ==="
$build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
$buildNum = $build.number
Write-Host "Build #$buildNum"

$cancelAt = if ($CancelAfterSeconds -gt 0) { (Get-Date).AddSeconds($CancelAfterSeconds) } else { $null }
$slurmJobId = $null

for ($i = 0; $i -lt $MaxPolls; $i++) {
    $b = Get-BuildStatus -Jenkins $jenkins -JobPath $jobPath -BuildNumber $buildNum
    $slurm = Get-SlurmQueue -Cfg $cfg
    $tail = Get-BuildConsoleTail -Jenkins $jenkins -JobPath $jobPath -BuildNumber $buildNum -Lines 20
    Write-Host "`n===== poll $i $(Get-Date -Format HH:mm:ss) #$($b.number) building=$($b.building) result=$($b.result) ====="
    if ($slurm) { $slurm -split "`n" | ForEach-Object { Write-Host "  slurm: $_" } }
    else { Write-Host '  slurm: (none)' }
    $tail | ForEach-Object { Write-Host "  $_" }

    if ($slurm -match '^\s*(\d+)\s') {
        $candidate = [regex]::Match($slurm, '^\s*(\d+)\s').Groups[1].Value
        if ($candidate) { $slurmJobId = $candidate }
    }

    if ($cancelAt -and (Get-Date) -ge $cancelAt -and $slurmJobId) {
        Write-Host "`n>>> scancel $slurmJobId (simulating external cancel) <<<" -ForegroundColor Yellow
        Invoke-SlurmSsh -Cfg $cfg -RemoteCommand "scancel $slurmJobId"
        $cancelAt = $null
    }

    if (-not $b.building) {
        Write-Host "`nBuild finished: $($b.result)"
        exit $(if ($b.result -eq 'SUCCESS') { 0 } else { 1 })
    }
    Start-Sleep -Seconds $PollSeconds
}

throw "Timeout after $MaxPolls polls - build still running"
