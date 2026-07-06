$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$cloudName = $cfg['SLURM_CLOUD_NAME']
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

$groovy = @"
import io.jenkins.plugins.slurm.*
import hudson.model.Label
def cloud = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
def req = Label.parseExpression("(rocmtest || miopen) && nogpu")
def t = cloud.jobTemplates?.find { it.name == "smoke-nogpu" }
return "canTake=" + t.canTake(req) + " canProvision=" + cloud.canProvision(new hudson.slaves.Cloud.CloudState(req, null))
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
