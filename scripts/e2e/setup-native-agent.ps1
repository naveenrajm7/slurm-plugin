# Configure Jenkins for native (non-Pyxis) agent e2e and create/update the pipeline job.
# Usage: powershell -File scripts/e2e/setup-native-agent.ps1

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-E2EConfig
Assert-E2EConfig -Cfg $cfg
$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL']

$cloud = $cfg['SLURM_CLOUD_NAME']
$folder = $cfg['E2E_FOLDER']
$workdir = $cfg['E2E_TEMPLATE_WORKDIR']
$partition = $cfg['E2E_PARTITION']
$feature = $cfg['E2E_FEATURE']
$jarPath = if ($cfg['E2E_NATIVE_AGENT_JAR']) { $cfg['E2E_NATIVE_AGENT_JAR'] } else { '/opt/jenkins/agent.jar' }
$javaPath = if ($cfg['E2E_NATIVE_JAVA_PATH']) { $cfg['E2E_NATIVE_JAVA_PATH'] } else { '/opt/jenkins/jdk-17/bin/java' }
$label = 'absol-native'
$jobName = 'native-agent-test'

Write-Host "=== Configure cloud-level native agent defaults on $cloud ==="
$groovyTemplate = @"
import io.jenkins.plugins.slurm.SlurmCloud
import io.jenkins.plugins.slurm.SlurmJobTemplate
import io.jenkins.plugins.slurm.AgentLaunchConfig

def cloud = jenkins.model.Jenkins.get().clouds.getAll(io.jenkins.plugins.slurm.SlurmCloud).find { it.name == '$($cloud)' }
if (!(cloud instanceof SlurmCloud)) {
  return 'ERROR: cloud not found'
}

def cloudAgent = new AgentLaunchConfig()
cloudAgent.javaPath = '$javaPath'
cloudAgent.jarPath = '$jarPath'
cloud.agent = cloudAgent

def t = cloud.jobTemplates?.find { it.label == '$label' }
if (t == null) {
  t = new SlurmJobTemplate('$label', '$label')
  cloud.jobTemplates = (cloud.jobTemplates ?: []) + t
}

t.partition = '$partition'
t.currentWorkingDirectory = '$workdir'
t.cpusPerTask = 2
t.memoryPerNode = 2048L
t.timeLimit = 30
t.constraints = '$feature'
t.pyxis = null
t.agent = null

cloud.jobTemplates = cloud.jobTemplates
jenkins.model.Jenkins.get().save()
return 'OK cloud agent + template $label'
"@

$result = Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovyTemplate
Write-Host $result

Write-Host "=== Deploy pipeline job $folder/$jobName ==="
$pipelinePath = Join-Path (Split-Path $PSScriptRoot -Parent | Split-Path -Parent) 'examples\native-agent-test.groovy'
$pipeline = Get-Content -Raw $pipelinePath
$pipelineB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pipeline))

$groovyJob = @"
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

def j = jenkins.model.Jenkins.get()
def folder = j.getItemByFullName('$folder')
if (folder == null) {
  return 'ERROR: folder not found: $folder'
}

def job = folder.getItem('$jobName')
if (job == null) {
  job = folder.createProject(WorkflowJob, '$jobName')
}
def script = new String(java.util.Base64.getDecoder().decode('$pipelineB64'), java.nio.charset.StandardCharsets.UTF_8)
job.definition = new CpsFlowDefinition(script, true)
job.save()
return 'OK job $jobName'
"@

$result = Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovyJob
Write-Host $result
Write-Host "Done. Run: powershell -File scripts/e2e/run-e2e.ps1 -Job $folder/$jobName"
