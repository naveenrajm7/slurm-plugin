$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$cloudName = $cfg['SLURM_CLOUD_NAME']
$gpuTemplate = if ($cfg['SMOKE_GPU_TEMPLATE_NAME']) { $cfg['SMOKE_GPU_TEMPLATE_NAME'] } else { 'smoke-gfx950' }
$gpuLabel = if ($cfg['SMOKE_GPU_NODE_LABEL']) { $cfg['SMOKE_GPU_NODE_LABEL'] } else { 'gfx950' }
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

$groovy = @"
import io.jenkins.plugins.slurm.SlurmCloud
import hudson.model.Label
import jenkins.model.Jenkins
def c = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
def t = c.jobTemplates.find { it.name == '$gpuTemplate' }
def req = Label.parseExpression("(rocmtest || miopen) && $gpuLabel")
return "canTake=" + t.canTake(req) + " label=" + t.label + " part=" + t.partition + " tres=" + t.tresPerJob
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
