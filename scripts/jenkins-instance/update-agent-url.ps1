# Update Slurm cloud Jenkins URL from config.env JENKINS_AGENT_URL.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$base = $cfg['JENKINS_URL']
$pass = $cfg['JENKINS_ADMIN_PASSWORD']
$user = $cfg['JENKINS_ADMIN_USER']
$cloudName = $cfg['SLURM_CLOUD_NAME']
$agentUrl = $cfg['JENKINS_AGENT_URL']

$jenkins = if ($pass -and $pass -ne 'unused') {
    Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
} else {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
    $headers = @{}
    $headers[$crumb.crumbRequestField] = $crumb.crumb
    @{ Session = $session; Headers = $headers; BaseUrl = $base.TrimEnd('/') }
}

$groovy = @"
import io.jenkins.plugins.slurm.SlurmCloud
import jenkins.model.Jenkins
def j = Jenkins.get()
def cloud = j.clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
if (!cloud) {
  return 'cloud-not-found:' + j.clouds.findAll { it instanceof SlurmCloud }.collect { it.name }
}
cloud.setJenkinsUrl('$agentUrl')
j.save()
return 'updated jenkinsUrl=' + cloud.jenkinsUrl
"@

$result = Invoke-JenkinsScript -Jenkins $jenkins -Groovy $groovy
Write-Host $result
if ("$result" -notmatch 'updated jenkinsUrl=') { exit 1 }
