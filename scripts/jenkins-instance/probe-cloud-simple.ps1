$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$cloudName = $cfg['SLURM_CLOUD_NAME']
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

Write-Host 'Plugin:' (Invoke-JenkinsScript -Jenkins $jenkins -Groovy 'jenkins.model.Jenkins.get().pluginManager.plugins.find{it.shortName=="slurm"}?.version ?: "not-loaded"')
Write-Host 'Classes:' (Invoke-JenkinsScript -Jenkins $jenkins -Groovy 'try { Class.forName("io.jenkins.plugins.slurm.AgentLaunchConfig"); return "AgentLaunchConfig=ok" } catch (Throwable t) { return "AgentLaunchConfig=missing" }')
Write-Host 'Cloud:'
$groovy = @"
import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins
def c = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' }
if (!c) return "no-cloud"
return c.name + " templates=" + c.jobTemplates?.size() + " agentDl=" + c.agent?.downloadJar
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
