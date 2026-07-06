$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$cfg = Get-InstanceConfig
$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL'] -User $cfg['JENKINS_ADMIN_USER'] -Password $cfg['JENKINS_ADMIN_PASSWORD']
$groovy = @'
import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins
def c = Jenkins.get().clouds.find { it instanceof SlurmCloud } as SlurmCloud
if (!c) return 'no cloud'
def t = c.jobTemplates ? c.jobTemplates[0] : null
if (!t) return 'no templates'
return "label=${t.label} partition=${t.partition} pyxis=${t.pyxis?.containerImage}"
'@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
