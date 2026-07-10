param([string]$BranchSuffix)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$folder = $c['WORKLOAD_FOLDER']
$jobName = $c['WORKLOAD_JOB']
$suffix = if ($BranchSuffix) { $BranchSuffix } elseif ($c['WORKLOAD_BRANCH_SUFFIX']) { $c['WORKLOAD_BRANCH_SUFFIX'] } else { $c['WORKLOAD_GIT_BRANCH'] }
if ([string]::IsNullOrWhiteSpace($suffix)) {
    throw 'Set -BranchSuffix or WORKLOAD_BRANCH_SUFFIX / WORKLOAD_GIT_BRANCH in config.env'
}
$groovy = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
def mb = (Jenkins.get().getItem('$folder') as Folder)?.getItem('$jobName')
def job = mb?.items?.find { it.name?.contains('$suffix') }
return job?.fullName ?: ''
"@
$fullName = (Invoke-JenkinsScript -Jenkins $j -Groovy $groovy) -replace '^Result:\s*', ''
if ([string]::IsNullOrWhiteSpace($fullName)) {
    throw "No multibranch job matching suffix: $suffix"
}
$jobPath = ConvertTo-JobPath -JobFullName $fullName

Write-Host "Job path: $jobPath"
try {
    $job = Invoke-JenkinsApi -Jenkins $j -Uri "$($j.BaseUrl)/$jobPath/api/json?tree=name,lastBuild[number,building,result,url],lastCompletedBuild[number,result]"
    Write-Host "lastBuild: #$($job.lastBuild.number) building=$($job.lastBuild.building) result=$($job.lastBuild.result)"
    Write-Host "url: $($job.lastBuild.url)"
    if ($job.lastBuild.number) {
        & "$PSScriptRoot\grep-build-keylines.ps1" -Build $job.lastBuild.number
    }
} catch {
    Write-Host "Job not found or not indexed yet: $_"
}
