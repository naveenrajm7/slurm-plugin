$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$cfg = Get-InstanceConfig
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg
$groovy = @'
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import com.cloudbees.hudson.plugins.folder.Folder

def sb = new StringBuilder()
Jenkins.instance.items.each { top ->
  if (top instanceof Folder) {
    top.items.each { job ->
      if (job instanceof WorkflowJob) {
        sb << top.name << '/' << job.name << '\n'
      }
    }
  } else if (top instanceof WorkflowJob) {
    sb << top.name << '\n'
  }
}
return sb.toString() ?: 'no jobs'
'@
Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
