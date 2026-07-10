# Find multibranch job by scanning folder children (branch names may contain /).
param([string]$BranchSuffix)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$folder = $c['WORKLOAD_FOLDER']
$jobName = $c['WORKLOAD_JOB']
$BranchSuffix = if ($BranchSuffix) { $BranchSuffix } elseif ($c['WORKLOAD_BRANCH_SUFFIX']) { $c['WORKLOAD_BRANCH_SUFFIX'] } else { $c['WORKLOAD_GIT_BRANCH'] }
if ([string]::IsNullOrWhiteSpace($BranchSuffix)) {
    throw 'Set -BranchSuffix or WORKLOAD_BRANCH_SUFFIX / WORKLOAD_GIT_BRANCH in config.env'
}

$groovy = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
def folder = Jenkins.get().getItem('$folder') as Folder
def mb = folder?.getItem('$jobName')
if (!mb) return 'multibranch job not found'
def hits = mb.items.findAll { it.name?.contains('$BranchSuffix') }
return hits.collect { it.fullName + ' -> build #' + (it.lastBuild?.number ?: 'none') + ' ' + it.lastBuild?.result }.join('\n') ?: 'no branch matching $BranchSuffix'
"@
Write-Host "=== Branch jobs ==="
Invoke-JenkinsScript -Jenkins $j -Groovy $groovy

$groovy2 = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
def folder = Jenkins.get().getItem('$folder') as Folder
def mb = folder?.getItem('$jobName')
def job = mb?.items?.find { it.name?.contains('$BranchSuffix') }
if (!job?.lastBuild) return 'no build'
return job.lastBuild.url
"@
$url = Invoke-JenkinsScript -Jenkins $j -Groovy $groovy2
Write-Host "Build URL: $url"

if ($url -match '/(\d+)/?$') {
    $bn = [int]$Matches[1]
    $rel = ($url -replace [regex]::Escape($j.BaseUrl.TrimEnd('/') + '/'), '').TrimEnd('/')
    $jobPath = $rel -replace "/$bn$", ''
    Write-Host "JobPath: $jobPath Build: $bn"
    $text = Invoke-JenkinsApi -Jenkins $j -Uri "$($j.BaseUrl)/$jobPath/$bn/consoleText"
    if ($text -is [byte[]]) { $text = [Text.Encoding]::UTF8.GetString($text) }
    $patterns = 'Slurm|agent|launch|ERROR|Exception|retention|Running on|Preflight|Static checks|Docker|Finished|doesn.t have label'
    "$text" -split "`n" | Select-String -Pattern $patterns | Select-Object -Last 35 | ForEach-Object { $_.Line }
}
