$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$cloudName = $c['SLURM_CLOUD_NAME']
$folder = $c['WORKLOAD_FOLDER']

$groovy = @"
import io.jenkins.plugins.slurm.*
import hudson.model.Label
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder

def cloud = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
def sb = new StringBuilder()
if (!cloud) return 'cloud missing'
sb.append('cloud=').append(cloud.name)
  .append(' jenkinsUrl=').append(cloud.jenkinsUrl)
  .append(' slurmUrl=').append(cloud.slurmUrl)
  .append(' creds=').append(cloud.credentialsId)
  .append(' maxAgents=').append(cloud.maxAgents)
  .append(' agents=').append(cloud.slurmAgents?.size() ?: 0)
  .append('\n')

def label = Label.parseExpression('(rocmtest || miopen) && nogpu')
sb.append('label.clouds=').append(label.clouds*.name)
sb.append(' assignable=').append(label.isAssignable())
sb.append(' canProvision=').append(cloud.canProvision(label))
sb.append('\n')

def folderItem = Jenkins.get().getItem('$folder')
if (folderItem instanceof Folder) {
  def fp = folderItem.properties.get(SlurmFolderProperty.class)
  sb.append('folderProperty=').append(fp ? fp.allowedClouds : 'none')
} else {
  sb.append('folder not found')
}
return sb.toString()
"@

Invoke-JenkinsScript -Jenkins $j -Groovy $groovy
