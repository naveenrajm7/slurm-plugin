param([string]$BranchSuffix)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$suffix = if ($BranchSuffix) { $BranchSuffix } elseif ($c['WORKLOAD_BRANCH_SUFFIX']) { $c['WORKLOAD_BRANCH_SUFFIX'] } else { $c['WORKLOAD_GIT_BRANCH'] }
if ([string]::IsNullOrWhiteSpace($suffix)) {
    throw 'Set -BranchSuffix or WORKLOAD_BRANCH_SUFFIX / WORKLOAD_GIT_BRANCH in config.env'
}

$groovy = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
def folder = Jenkins.get().getItem('${c['WORKLOAD_FOLDER']}') as Folder
def mb = folder?.getItem('${c['WORKLOAD_JOB']}')
def job = mb?.items?.find { it.name?.contains('$suffix') }
if (!job) return 'branch job not found'
def b = job.lastBuild
if (!b) return 'no builds'
def tail = Jenkins.get().getItemByFullName(job.fullName)?.getBuildByNumber(b.number)?.logFile?.text?.readLines()?.takeRight(30)?.join('\n')
return "build #\${b.number} result=\${b.result} building=\${b.isBuilding()}\n--- tail ---\n\${tail}"
"@
Invoke-JenkinsScript -Jenkins $j -Groovy $groovy

$groovy2 = @"
import jenkins.model.Jenkins
import io.jenkins.plugins.slurm.*
def cloud = '${c['SLURM_CLOUD_NAME']}'
def nodes = Jenkins.get().nodes.findAll { it.name?.contains(cloud) }
return "agentCount=\${nodes.size()} names=\${nodes*.name.join(', ')}"
"@
Write-Host "`n=== Agents ==="
Invoke-JenkinsScript -Jenkins $j -Groovy $groovy2

$groovy3 = @"
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries
def libs = GlobalLibraries.get().libraries
return libs.collect { it.name + '@' + it.defaultVersion + ' retriever=' + it.retriever.class.simpleName }.join('\n')
"@
Write-Host "`n=== Shared libraries ==="
Invoke-JenkinsScript -Jenkins $j -Groovy $groovy3
