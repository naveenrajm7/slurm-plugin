$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$cloudName = $c['SLURM_CLOUD_NAME']

$groovy = @"
import io.jenkins.plugins.slurm.*
import hudson.model.Label
import hudson.slaves.Cloud
import jenkins.model.Jenkins

def cloud = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
def exprs = [
  '(rocmtest || miopen) && nogpu',
  '(rocmtest||miopen)&&(nogpu)',
  '(rocmtest || miopen) && (nogpu)'
]
def sb = new StringBuilder()
exprs.each { e ->
  def l = Label.parseExpression(e)
  def state = new Cloud.CloudState(l, null)
  sb.append(e).append(' -> clouds=').append(l.clouds*.name)
    .append(' canProvision=').append(cloud?.canProvision(state))
    .append(' assignable=').append(l.isAssignable()).append('\n')
}
def t = cloud?.jobTemplates?.find { it.name == 'wl-nogpu' }
sb.append('wl-nogpu canTake(expr)=').append(t?.canTake(Label.parseExpression('(rocmtest || miopen) && nogpu')))
return sb.toString()
"@

Write-Host "=== Provision probe ==="
Invoke-JenkinsScript -Jenkins $j -Groovy $groovy
