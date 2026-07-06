# Configure Jenkins for Slurm job-options coverage e2e and create/update the pipeline job.
# Usage: powershell -File scripts/e2e/setup-options-coverage.ps1

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-E2EConfig
Assert-E2EConfig -Cfg $cfg
$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL']

$cloud = $cfg['SLURM_CLOUD_NAME']
$folder = $cfg['E2E_FOLDER']
$workdir = $cfg['E2E_TEMPLATE_WORKDIR']
$cpuImage = $cfg['E2E_CPU_CONTAINER_IMAGE']
$label = 'absol-options'
$jobName = 'options-coverage-test'

Write-Host "=== Configure template label=$label on cloud $cloud ==="
$groovyTemplate = @"
import io.jenkins.plugins.slurm.SlurmCloud
import io.jenkins.plugins.slurm.SlurmJobTemplate
import io.jenkins.plugins.slurm.PyxisConfig

def cloud = jenkins.model.Jenkins.get().clouds.getAll(io.jenkins.plugins.slurm.SlurmCloud).find { it.name == '$($cloud)' }
if (!(cloud instanceof SlurmCloud)) {
  return 'ERROR: cloud not found'
}

def t = cloud.jobTemplates?.find { it.label == '$label' }
if (t == null) {
  t = new SlurmJobTemplate('options-template', '$label')
  if (cloud.jobTemplates == null) {
    cloud.jobTemplates = new ArrayList()
  }
  cloud.jobTemplates.add(t)
}
t.setPartition('jenkins-e2e')
t.setAccount('ags')
t.setQos('normal')
t.setConstraints('jenkins-e2e')
t.setCpusPerTask(2)
t.setMemoryPerNode(2048L)
t.setTimeLimit(30)
t.setCurrentWorkingDirectory('$workdir')
t.setRequiredNodes('cgy-absol')
t.setExcludedNodes('cgy-clefairy,cgy-geodude')
t.setEnvironment('["JENKINS_E2E_TEMPLATE=static-template"]')
t.setIdleMinutes(0)
t.setRunOnce(true)

def pyxis = t.pyxis ?: new PyxisConfig()
pyxis.setContainerImage('$cpuImage')
pyxis.setContainerMountHome(true)
pyxis.setContainerRemap(true)
t.setPyxis(pyxis)

jenkins.model.Jenkins.get().save()
return "template label=`${t.label} partition=`${t.partition} account=`${t.account}"
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovyTemplate

Write-Host "=== Create/update pipeline job $folder/$jobName ==="
$pipelinePath = Resolve-Path "$PSScriptRoot\..\..\examples\options-coverage-test.groovy"
$pipelineBytes = [System.IO.File]::ReadAllBytes($pipelinePath)
$pipelineB64 = [Convert]::ToBase64String($pipelineBytes)

$groovyJob = @"
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

def folder = jenkins.model.Jenkins.get().getItemByFullName('$folder')
if (folder == null) {
  return 'ERROR: folder not found'
}
def job = folder.getItem('$jobName')
if (job == null) {
  job = folder.createProject(WorkflowJob.class, '$jobName')
}
def script = new String(java.util.Base64.decoder.decode('$pipelineB64'), java.nio.charset.StandardCharsets.UTF_8)
job.setDefinition(new CpsFlowDefinition(script, true))
job.save()
return job.fullName
"@

$result = Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovyJob
Write-Host "Job: $result"
Write-Host "Done. Run: powershell -File scripts/e2e/run-e2e.ps1 -Jobs options-coverage-test"
