# Configure label smoke templates and job on existing Slurm cloud.
#
# Usage:
#   pwsh -File scripts/jenkins-instance/configure-smoke.ps1

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig

$base = $cfg['JENKINS_URL']
$user = $cfg['JENKINS_ADMIN_USER']
$pass = $cfg['JENKINS_ADMIN_PASSWORD']

Write-Host "=== Configure label smoke (templates + job) ==="
Write-Host "Jenkins: $base"
Write-Host "Cloud: $($cfg['SLURM_CLOUD_NAME'])"

if (-not (Test-JenkinsUp -BaseUrl $base)) {
    throw "Jenkins not reachable at $base"
}

$jenkins = if ($pass -and $pass -ne 'unused') {
    Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
} else {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
    $headers = @{}
    $headers[$crumb.crumbRequestField] = $crumb.crumb
    @{ Session = $session; Headers = $headers; BaseUrl = $base.TrimEnd('/') }
}

if (-not (Test-SlurmPluginLoaded -Jenkins $jenkins)) {
    throw 'Slurm plugin not loaded. Run deploy.ps1 first.'
}

$groovyPath = Join-Path $PSScriptRoot 'configure-smoke.groovy'
$groovy = Expand-JobTemplate -TemplatePath $groovyPath -Cfg $cfg

$result = Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
if ("$result" -notmatch 'smoke-templates-ok') { throw "Template configure failed: $result" }
Write-Host $result

$folder = $cfg['E2E_FOLDER']
$jobName = $cfg['E2E_JOB_TEMPLATE_TEST']

$folderGroovy = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
def j = Jenkins.get()
if (!j.getItem('${folder}')) {
  j.createProject(Folder.class, '${folder}')
}
j.save()
return 'folder-ok'
"@
Write-Host "Creating folder ${folder}..."
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $folderGroovy | Out-Null

$scriptText = Get-Content -Raw -LiteralPath "$PSScriptRoot\jobs\label-smoke.groovy"
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($scriptText))
$jobGroovy = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import java.util.Base64

def j = Jenkins.get()
def folder = j.getItem('${folder}') as Folder
folder.getItems().findAll { it.name == '${jobName}' }.each { it.delete() }
def job = folder.getItem('${jobName}') as WorkflowJob
if (!job) {
  job = new WorkflowJob(folder, '${jobName}')
  folder.add(job, '${jobName}')
}
def script = new String(Base64.decoder.decode('${b64}'), 'UTF-8')
job.setDefinition(new CpsFlowDefinition(script, true))
job.save()
j.save()
return 'job-ok'
"@
Write-Host "Creating job ${folder}/${jobName}..."
$jobResult = Invoke-JenkinsScript -Jenkins $jenkins -Groovy $jobGroovy
if ("$jobResult" -notmatch 'job-ok') { throw "Job create failed: $jobResult" }
Write-Host "Configuration complete."
