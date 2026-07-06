$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

Write-Host 'Plugin:' (Invoke-JenkinsScript -Jenkins $jenkins -Groovy 'jenkins.model.Jenkins.get().pluginManager.plugins.find{it.shortName=="slurm"}?.version ?: "not-loaded"')
Write-Host 'Cloud:'
Invoke-JenkinsScript -Jenkins $jenkins -Groovy @'
import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins
def c = Jenkins.get().clouds.find { it instanceof SlurmCloud }
if (!c) return "no-cloud"
def sb = c.name + " jenkinsUrl=" + c.jenkinsUrl + " rest=" + c.slurmRestApiUrl + " templates=" + c.jobTemplates?.size() + "\n"
c.jobTemplates?.each { t -> sb += "  " + t.name + " label=" + t.label + " part=" + t.partition + " tres=" + t.tresPerJob + " agent=" + (t.agent?.downloadJar ?: false) + "\n" }
if (c.agent) sb += "cloud-agent downloadJar=" + c.agent.downloadJar + " java=" + c.agent.javaPath + "\n"
return sb
'@
