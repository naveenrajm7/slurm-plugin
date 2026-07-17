$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$cfg = Get-InstanceConfig
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

$cloudName = $cfg['SLURM_CLOUD_NAME']
$templateName = $cfg['LEGATO_TEMPLATE_NAME']
$legatoLabel = $cfg['LEGATO_LABEL']
if (-not $cloudName -or -not $templateName -or -not $legatoLabel) {
    throw 'Set SLURM_CLOUD_NAME, LEGATO_TEMPLATE_NAME, and LEGATO_LABEL in config.env'
}

Write-Host '=== Legato label match ==='
$groovy = @"
import io.jenkins.plugins.slurm.*
import hudson.model.Label
import jenkins.model.Jenkins
def cloud = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
def expr = Label.parseExpression('$($legatoLabel -replace "'", "''")')
def t = cloud.jobTemplates?.find { it.name == '$templateName' }
def sb = new StringBuilder()
sb << "cloud=" << cloud.name << " canProvision=" << cloud.canProvision(expr) << "\n"
if (t) {
  sb << "template canTake=" << t.canTake(expr) << " atoms=" << t.labelAtoms << "\n"
  sb << "environment=" << t.environment << "\n"
  sb << "workdir=" << t.currentWorkingDirectory << " mode=" << t.nodeUsageMode << "\n"
}
sb << "getTemplatesFor=" << SlurmJobTemplateUtils.getTemplatesFor(cloud, expr)*.name << "\n"
return sb.toString()
"@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy

Write-Host '=== Queue ==='
Invoke-JenkinsScript -Jenkins $jenkins -Groovy @'
import jenkins.model.Jenkins
def q = Jenkins.get().queue.items
return q.collect { it.task.name + " label=" + it.task.label?.name + " why=" + it.getWhy() }.join("\n") ?: "empty"
'@
