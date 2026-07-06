$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$cloudName = $cfg['SLURM_CLOUD_NAME']
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

$groovy = @"
import io.jenkins.plugins.slurm.*
import hudson.model.Label
def cloud = Jenkins.instance.clouds.find { it.name == '$cloudName' }
def t = cloud.jobTemplates.find { it.name == 'smoke-nogpu' }
def req = Label.parseExpression("(rocmtest || miopen) && nogpu")
def atoms = t.labelAtoms
return "label=[" + t.label + "] atoms=" + atoms.collect { it.name } + " match=" + req.matches(atoms) + " canTake=" + t.canTake(req)
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
