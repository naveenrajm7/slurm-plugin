$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$cloudName = $cfg['SLURM_CLOUD_NAME']
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

$groovy = @"
import io.jenkins.plugins.slurm.SlurmCloud
import jenkins.model.Jenkins
def c = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
if (!c) return 'cloud-not-found'
def sb = c.name + ' jenkinsUrl=' + c.jenkinsUrl + '\n'
c.jobTemplates?.each { t -> sb += '  ' + t.name + ' label=' + t.label + ' tres=' + t.tresPerJob + '\n' }
return sb
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
