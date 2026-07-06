$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$cloudName = $cfg['SLURM_CLOUD_NAME']
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

Write-Host '=== Plugin ==='
Invoke-JenkinsScript -Jenkins $jenkins -Groovy 'jenkins.model.Jenkins.get().pluginManager.plugins.find{it.shortName=="slurm"}?.version'

Write-Host '=== Clouds ==='
Invoke-JenkinsScript -Jenkins $jenkins -Groovy @'
import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins
def sb = new StringBuilder()
Jenkins.get().clouds.each { c ->
  if (c instanceof SlurmCloud) {
    sb << c.name << " templates=" << c.jobTemplates?.size() << " agentDl=" << c.agent?.downloadJar << "\n"
    c.jobTemplates?.each { t -> sb << "  " << t.name << " label=[" << t.label << "]\n" }
  }
}
return sb.toString()
'@

Write-Host '=== Label match (ck-nogpu vs CK expression) ==='
$groovy = @"
import io.jenkins.plugins.slurm.*
import hudson.model.Label
import jenkins.model.Jenkins
def cloud = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
if (!cloud) return "cloud not found"
def req = Label.parseExpression("(rocmtest || miopen) && nogpu")
def t = cloud.jobTemplates?.find { it.name == "ck-nogpu" }
if (!t) return "template ck-nogpu not found; names=" + cloud.jobTemplates*.name
return "canTake(Label)=" + t.canTake(req) + " atoms=" + t.labelAtoms + " cloudsForLabel=" + req.clouds*.name
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy

Write-Host '=== getTemplatesFor ==='
$groovy2 = @"
import io.jenkins.plugins.slurm.*
import hudson.model.Label
import jenkins.model.Jenkins
def cloud = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
def req = Label.parseExpression("(rocmtest || miopen) && nogpu")
def list = SlurmJobTemplateUtils.getTemplatesFor(cloud, req)
return "matched=" + list*.name
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy2
