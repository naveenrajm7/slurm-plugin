$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$folder = $c['WORKLOAD_FOLDER']
$jobName = $c['WORKLOAD_JOB']
$branch = if ($c['WORKLOAD_GIT_BRANCH']) { $c['WORKLOAD_GIT_BRANCH'] } else { 'develop' }
$cloudName = $c['SLURM_CLOUD_NAME']
$jobPath = ConvertTo-JobPath -JobFullName "$folder/$jobName/$branch"

$job = Invoke-JenkinsApi -Jenkins $j -Uri "$($j.BaseUrl)/$jobPath/api/json?tree=lastBuild[number,building,result,url],builds[number,building,result]"
Write-Host "lastBuild: #$($job.lastBuild.number) building=$($job.lastBuild.building) result=$($job.lastBuild.result)"

$groovy = @"
import jenkins.model.Jenkins
import io.jenkins.plugins.slurm.*
def nodes = Jenkins.get().nodes.findAll { it.name?.contains('$cloudName') }
return nodes.collect { n ->
  def c = n.toComputer()
  def a = n instanceof SlurmAgent ? (SlurmAgent)n : null
  "\${n.name} online=\${c?.online} connecting=\${c?.connecting} label=\${n.labelString} desc=\${n.nodeDescription?.take(60)}"
}.join('\n') ?: 'no slurm agents'
"@
Write-Host "`n=== Slurm agents ==="
Invoke-JenkinsScript -Jenkins $j -Groovy $groovy

$groovy2 = @'
import jenkins.model.Jenkins
def p = Jenkins.instance.pluginManager.plugins.find { it.shortName == 'github-branch-source' }
return p ? "github-branch-source ${p.version}" : 'github-branch-source MISSING'
'@
Write-Host "`n=== Plugins ==="
Invoke-JenkinsScript -Jenkins $j -Groovy $groovy2
