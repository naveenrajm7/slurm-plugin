$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$cfg = Get-InstanceConfig
$jenkins = Get-JenkinsSession -BaseUrl $cfg['JENKINS_URL'] -User $cfg['JENKINS_ADMIN_USER'] -Password $cfg['JENKINS_ADMIN_PASSWORD']
$folder = $cfg['E2E_FOLDER']
$jobs = @($cfg['E2E_JOB_TEMPLATE_TEST'], $cfg['E2E_JOB_DECLARATIVE_JSON'])
foreach ($name in $jobs) {
    $path = ConvertTo-JobPath -JobFullName "$folder/$name"
    Write-Host "Job path: $path"
    try {
        $info = Invoke-RestMethod -Uri "$($jenkins.BaseUrl)/$path/api/json?tree=name,url" -Headers $jenkins.Headers -WebSession $jenkins.Session
        Write-Host "  exists: $($info.name) $($info.url)"
    } catch {
        Write-Host "  MISSING: $_"
    }
}
