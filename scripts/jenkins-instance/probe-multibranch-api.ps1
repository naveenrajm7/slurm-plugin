$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$folder = $c['WORKLOAD_FOLDER']
$jobName = $c['WORKLOAD_JOB']
$branch = $c['WORKLOAD_GIT_BRANCH']
if ([string]::IsNullOrWhiteSpace($branch)) { $branch = 'develop' }
$mbPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName")
$info = Invoke-JenkinsApi -Jenkins $j -Uri "$($j.BaseUrl)/$mbPath/api/json?tree=jobs[name]"
$names = @($info.jobs | ForEach-Object { $_.name })
Write-Host "Branch jobs: $($names.Count)"
if ($names -contains $branch) {
    Write-Host "develop present: yes"
} else {
    Write-Host "develop present: no"
    $names | Select-Object -First 10 | ForEach-Object { Write-Host "  sample: $_" }
}
