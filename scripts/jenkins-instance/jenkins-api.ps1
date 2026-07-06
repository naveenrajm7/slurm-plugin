# Jenkins REST helpers for dedicated-instance harness (Windows / PowerShell).
# Dot-source: . "$PSScriptRoot\jenkins-api.ps1"

function Get-InstanceConfig {
    param([string]$ConfigPath = "$PSScriptRoot\config.env")
    if (-not (Test-Path $ConfigPath)) {
        throw @"
Missing $ConfigPath
Copy scripts/jenkins-instance/config.env.example to scripts/jenkins-instance/config.env and set your values.
config.env is gitignored and must not be committed.
"@
    }
    $cfg = @{}
    Get-Content $ConfigPath | ForEach-Object {
        if ($_ -match '^\s*#') { return }
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $val = $Matches[2].Trim()
            if ($val.Length -ge 2 -and $val.StartsWith('"') -and $val.EndsWith('"')) {
                $val = $val.Substring(1, $val.Length - 2)
            }
            $cfg[$Matches[1]] = $val
        }
    }
    if (-not $cfg['JENKINS_URL'] -and $cfg['JENKINS_HOSTNAME'] -and $cfg['JENKINS_HTTP_PORT']) {
        $cfg['JENKINS_URL'] = "http://$($cfg['JENKINS_HOSTNAME']):$($cfg['JENKINS_HTTP_PORT'])"
    }
    if (-not $cfg['JENKINS_AGENT_URL'] -and $cfg['JENKINS_URL']) {
        $agent = $cfg['JENKINS_URL'].TrimEnd('/')
        $cfg['JENKINS_AGENT_URL'] = "$agent/"
    }
    return $cfg
}

function Assert-InstanceConfig {
    param(
        [hashtable]$Cfg,
        [string[]]$ExtraRequired = @()
    )
    $required = @(
        'JENKINS_SSH_HOST', 'JENKINS_URL', 'JENKINS_AGENT_URL',
        'JENKINS_ADMIN_USER', 'JENKINS_ADMIN_PASSWORD',
        'SLURM_SSH_HOST', 'SLURM_CLOUD_NAME', 'SLURM_REST_URL',
        'SLURM_CREDENTIALS_ID', 'E2E_FOLDER', 'E2E_TEMPLATE_LABEL', 'E2E_TEMPLATE_WORKDIR'
    ) + $ExtraRequired
    foreach ($key in $required) {
        $val = $Cfg[$key]
        if ([string]::IsNullOrWhiteSpace($val)) {
            throw "Set $key in scripts/jenkins-instance/config.env"
        }
        if ($val -match '^(YOUR_|your-|CHANGE_ME)') {
            throw "Replace placeholder value for $key in scripts/jenkins-instance/config.env"
        }
    }
}

function Invoke-InstanceSsh {
    param(
        [hashtable]$Cfg,
        [string]$RemoteCommand,
        [string]$HostKey = 'JENKINS_SSH_HOST'
    )
    $sshHost = $Cfg[$HostKey]
    $opts = if ($Cfg['JENKINS_SSH_OPTS']) { $Cfg['JENKINS_SSH_OPTS'] } else { '-o BatchMode=yes -o ConnectTimeout=10' }
    $escaped = $RemoteCommand -replace "'", "'\\''"
    wsl -e bash -lc "ssh $opts '${sshHost}' '${escaped}'"
}

function Invoke-InstanceScp {
    param(
        [hashtable]$Cfg,
        [string]$LocalPath,
        [string]$RemotePath
    )
    $sshHost = $Cfg['JENKINS_SSH_HOST']
    $opts = if ($Cfg['JENKINS_SSH_OPTS']) { $Cfg['JENKINS_SSH_OPTS'] } else { '-o BatchMode=yes -o ConnectTimeout=10' }
    $wslLocal = (wsl -e wslpath -a $LocalPath).Trim()
    wsl -e bash -lc "scp ${opts} '${wslLocal}' '${sshHost}:${RemotePath}'"
}

function Get-BasicAuthHeader {
    param([string]$User, [string]$Password)
    $pair = "${User}:${Password}"
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($pair)
  return @{ Authorization = "Basic $([Convert]::ToBase64String($bytes))" }
}

function Connect-JenkinsScriptConsole {
    param([hashtable]$Cfg)
    $base = $Cfg['JENKINS_URL']
    $user = $Cfg['JENKINS_ADMIN_USER']
    $pass = $Cfg['JENKINS_ADMIN_PASSWORD']
    if ($pass -and $pass -ne 'unused') {
        return Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
    }
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
    $headers = @{}
    $headers[$crumb.crumbRequestField] = $crumb.crumb
    return @{ Session = $session; Headers = $headers; BaseUrl = $base.TrimEnd('/') }
}

function Get-JenkinsSession {
    param(
        [string]$BaseUrl,
        [string]$User,
        [string]$Password
    )
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $headers = @{}
    if ($User -and $Password) {
        $auth = Get-BasicAuthHeader -User $User -Password $Password
        foreach ($k in $auth.Keys) { $headers[$k] = $auth[$k] }
    }
    $crumbParams = @{ Uri = "$BaseUrl/crumbIssuer/api/json"; WebSession = $session }
    if ($headers.Count -gt 0) { $crumbParams['Headers'] = $headers }
    $crumb = Invoke-RestMethod @crumbParams
    $headers[$crumb.crumbRequestField] = $crumb.crumb
    return @{ Session = $session; Headers = $headers; BaseUrl = $BaseUrl.TrimEnd('/') }
}

function Invoke-JenkinsApi {
    param(
        [hashtable]$Jenkins,
        [string]$Uri,
        [string]$Method = 'Get',
        [string]$Body,
        [string]$ContentType
    )
    $params = @{
        Uri        = $Uri
        Method     = $Method
        Headers    = $Jenkins.Headers
        WebSession = $Jenkins.Session
        TimeoutSec = 120
    }
    if ($Body) { $params['Body'] = $Body }
    if ($ContentType) { $params['ContentType'] = $ContentType }
    return Invoke-RestMethod @params
}

function Invoke-JenkinsScript {
    param([hashtable]$Jenkins, [string]$Groovy)
    $body = "script=$([uri]::EscapeDataString($Groovy))"
    Invoke-JenkinsApi -Jenkins $Jenkins -Uri "$($Jenkins.BaseUrl)/scriptText" -Method Post `
        -Body $body -ContentType 'application/x-www-form-urlencoded'
}

function Test-JenkinsUp {
    param([string]$BaseUrl, [int]$TimeoutSec = 5)
    try {
        return (Invoke-WebRequest -Uri "$BaseUrl/api/json" -UseBasicParsing -TimeoutSec $TimeoutSec).StatusCode -eq 200
    } catch { return $false }
}

function Wait-JenkinsReady {
    param(
        [string]$BaseUrl,
        [string]$User,
        [string]$Password,
        [int]$MaxAttempts = 60,
        [int]$SleepSeconds = 5
    )
    for ($i = 0; $i -lt $MaxAttempts; $i++) {
        if (Test-JenkinsUp -BaseUrl $BaseUrl) {
            try {
                $null = Get-JenkinsSession -BaseUrl $BaseUrl -User $User -Password $Password
                return
            } catch {
                Write-Host "Jenkins up but not ready for auth (attempt $i)..."
            }
        } else {
            Write-Host "Waiting for Jenkins at $BaseUrl (attempt $i)..."
        }
        Start-Sleep -Seconds $SleepSeconds
    }
    throw "Jenkins not ready at $BaseUrl after $($MaxAttempts * $SleepSeconds)s"
}

function Install-JenkinsPluginHpi {
    param(
        [hashtable]$Jenkins,
        [hashtable]$Cfg,
        [string]$HpiPath
    )
    if (-not (Test-Path $HpiPath)) { throw "Plugin not found: $HpiPath" }
    $remoteDir = if ($Cfg['JENKINS_REMOTE_DIR']) { $Cfg['JENKINS_REMOTE_DIR'] } else { '/tmp/jenkins-instance' }
    Invoke-InstanceScp -Cfg $Cfg -LocalPath $HpiPath -RemotePath "${remoteDir}/slurm.hpi"
    $remoteCmd = "docker cp ${remoteDir}/slurm.hpi slurm-jenkins:/var/jenkins_home/plugins/slurm.jpi && docker restart slurm-jenkins"
    Invoke-InstanceSsh -Cfg $Cfg -RemoteCommand $remoteCmd
    Wait-JenkinsReady -BaseUrl $Jenkins.BaseUrl -User $Cfg['JENKINS_ADMIN_USER'] -Password $Cfg['JENKINS_ADMIN_PASSWORD']
}

function Test-SlurmPluginLoaded {
    param([hashtable]$Jenkins)
    $groovy = @'
return jenkins.model.Jenkins.get().pluginManager.plugins.find { it.shortName == 'slurm' } != null
'@
    return [bool](Invoke-JenkinsScript -Jenkins $Jenkins -Groovy $groovy)
}

function Get-SlurmJwtToken {
    param([hashtable]$Cfg)
    if ($Cfg['SLURM_JWT_TOKEN']) { return $Cfg['SLURM_JWT_TOKEN'].Trim() }
    $sshHost = $Cfg['SLURM_SSH_HOST']
    $opts = if ($Cfg['SLURM_SSH_OPTS']) { $Cfg['SLURM_SSH_OPTS'] } else { '-o BatchMode=yes -o ConnectTimeout=10' }
    $raw = wsl -e bash -lc "ssh ${opts} '${sshHost}' 'scontrol token lifespan=3600 2>/dev/null' | sed -n 's/^SLURM_JWT=//p'"
    $token = "$raw".Trim()
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "Could not obtain SLURM_JWT_TOKEN. Set SLURM_JWT_TOKEN in config.env or ensure scontrol token works on ${sshHost}"
    }
    return $token
}

function Expand-JobTemplate {
    param(
        [string]$TemplatePath,
        [hashtable]$Cfg
    )
    $text = Get-Content -Raw -LiteralPath $TemplatePath
    foreach ($key in $Cfg.Keys) {
        $text = $text -replace [regex]::Escape('${' + $key + '}'), [string]$Cfg[$key]
    }
    return $text
}

function Invoke-ConfigureInstance {
    param(
        [hashtable]$Jenkins,
        [hashtable]$Cfg
    )
    Write-Host "Fetching Slurm JWT token..."
    $token = Get-SlurmJwtToken -Cfg $Cfg
    $escapedToken = $token -replace "\\", "\\\\" -replace "'", "\\'"
    $cloud = $Cfg['SLURM_CLOUD_NAME']
    $rest = $Cfg['SLURM_REST_URL']
    $credId = $Cfg['SLURM_CREDENTIALS_ID']
    $agentUrl = $Cfg['JENKINS_AGENT_URL']
    $jenkinsUrl = $Cfg['JENKINS_URL'].TrimEnd('/')
    $label = $Cfg['E2E_TEMPLATE_LABEL']
    $partition = if ($Cfg['E2E_TEMPLATE_PARTITION']) { $Cfg['E2E_TEMPLATE_PARTITION'] } else { 'cpu' }
    $cpus = if ($Cfg['E2E_TEMPLATE_CPUS']) { $Cfg['E2E_TEMPLATE_CPUS'] } else { '16' }
    $mem = if ($Cfg['E2E_TEMPLATE_MEMORY_MB']) { $Cfg['E2E_TEMPLATE_MEMORY_MB'] } else { '32768' }
    $workdir = $Cfg['E2E_TEMPLATE_WORKDIR']
    $folder = $Cfg['E2E_FOLDER']
    $timeLimit = if ($Cfg['E2E_TIME_LIMIT_MINUTES']) { $Cfg['E2E_TIME_LIMIT_MINUTES'] } else { '55' }
    $reservation = if ($Cfg['E2E_RESERVATION']) { $Cfg['E2E_RESERVATION'] } else { '' }
    $containerImage = $Cfg['E2E_CPU_CONTAINER_IMAGE']

    $coreGroovy = @"
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.util.Secret
import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import jenkins.model.JenkinsLocationConfiguration

def j = Jenkins.get()
JenkinsLocationConfiguration.get().setUrl('${jenkinsUrl}/')
JenkinsLocationConfiguration.get().save()

def store = j.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()
def existing = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
  StringCredentials.class, j, null, java.util.Collections.emptyList())
existing.findAll { it.id == '${credId}' }.each { store.removeCredentials(Domain.global(), it) }
store.addCredentials(Domain.global(), new StringCredentialsImpl(
  CredentialsScope.GLOBAL, '${credId}', 'Slurm JWT', Secret.fromString('${escapedToken}')))

def cloud = j.clouds.find { it instanceof SlurmCloud && it.name == '${cloud}' } as SlurmCloud
if (!cloud) {
  cloud = new SlurmCloud('${cloud}', '${rest}', '${credId}', '${partition}', 10, 60)
  j.clouds.add(cloud)
}
cloud.setJenkinsUrl('${agentUrl}')
def templates = cloud.getJobTemplates()
templates.clear()
def template = new SlurmJobTemplate()
template.setName('${label}-template')
template.setLabel('${label}')
template.setPartition('${partition}')
template.setCpusPerTask(${cpus} as Integer)
template.setMemoryPerNode(${mem} as Long)
template.setCurrentWorkingDirectory('${workdir}')
template.setTimeLimit(${timeLimit} as Integer)
if ('${reservation}') {
  template.setReservation('${reservation}')
}
if ('${containerImage}') {
  def pyxis = new PyxisConfig()
  pyxis.setContainerImage('${containerImage}')
  pyxis.setContainerMountHome(true)
  pyxis.setContainerWritable(false)
  pyxis.setContainerRemap(true)
  template.setPyxis(pyxis)
}
templates.add(template)
cloud.setJobTemplates(templates)
j.save()
return 'cloud-ok'
"@
    Write-Host "Configuring Jenkins location, credentials, and Slurm cloud..."
    $result = Invoke-JenkinsScript -Jenkins $Jenkins -Groovy $coreGroovy
    if ("$result" -notmatch 'cloud-ok') { throw "Cloud configure failed: $result" }

    $folderGroovy = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
def j = Jenkins.get()
if (!j.getItem('${folder}')) {
  j.createProject(Folder.class, '${folder}')
}
j.save()
return 'folder-ok'
"@
    Write-Host "Creating folder ${folder}..."
    Invoke-JenkinsScript -Jenkins $Jenkins -Groovy $folderGroovy | Out-Null

    foreach ($pair in @(
        @{ Name = $Cfg['E2E_JOB_TEMPLATE_TEST']; File = 'template-test.groovy' },
        @{ Name = $Cfg['E2E_JOB_DECLARATIVE_JSON']; File = 'dec-json-test.groovy' }
    )) {
        $jobName = $pair.Name
        if ([string]::IsNullOrWhiteSpace($jobName)) { continue }
        $scriptText = Expand-JobTemplate -TemplatePath "$PSScriptRoot\jobs\$($pair.File)" -Cfg $Cfg
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($scriptText))
        $jobGroovy = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import java.util.Base64

def j = Jenkins.get()
def folder = j.getItem('${folder}') as Folder
folder.getItems().findAll { it.name == '' || it.name == '$jobName' }.each { it.delete() }
def job = folder.getItem('$jobName') as WorkflowJob
if (!job) {
  job = new WorkflowJob(folder, '$jobName')
  folder.add(job, '$jobName')
}
def script = new String(Base64.decoder.decode('${b64}'), 'UTF-8')
job.setDefinition(new CpsFlowDefinition(script, true))
job.save()
j.save()
return 'job-$jobName-ok'
"@
        Write-Host "Creating job $jobName..."
        Invoke-JenkinsScript -Jenkins $Jenkins -Groovy $jobGroovy | Out-Null
    }
}

function Start-JenkinsBuild {
    param([hashtable]$Jenkins, [string]$JobPath)
    Invoke-JenkinsApi -Jenkins $Jenkins -Uri "$($Jenkins.BaseUrl)/$JobPath/build?delay=0" -Method Post | Out-Null
    Start-Sleep -Seconds 2
    return Invoke-JenkinsApi -Jenkins $Jenkins -Uri "$($Jenkins.BaseUrl)/$JobPath/lastBuild/api/json?tree=number,building,url,result"
}

function Get-BuildStatus {
    param([hashtable]$Jenkins, [string]$JobPath, [int]$BuildNumber)
    Invoke-JenkinsApi -Jenkins $Jenkins -Uri "$($Jenkins.BaseUrl)/$JobPath/$BuildNumber/api/json?tree=number,building,result,duration,url"
}

function Get-BuildConsoleTail {
    param([hashtable]$Jenkins, [string]$JobPath, [int]$BuildNumber, [int]$Lines = 25)
    $text = (Invoke-JenkinsApi -Jenkins $Jenkins -Uri "$($Jenkins.BaseUrl)/$JobPath/$BuildNumber/consoleText")
    if ($text -is [byte[]]) { $text = [Text.Encoding]::UTF8.GetString($text) }
    return ("$text" -split "`n" | Select-Object -Last $Lines)
}

function Invoke-SlurmSsh {
    param([hashtable]$Cfg, [string]$RemoteCommand)
    $sshHost = $Cfg['SLURM_SSH_HOST']
    $opts = if ($Cfg['SLURM_SSH_OPTS']) { $Cfg['SLURM_SSH_OPTS'] } else { '-o BatchMode=yes -o ConnectTimeout=10' }
    $escaped = $RemoteCommand -replace "'", "'\\''"
    wsl -e bash -lc "ssh $opts '${sshHost}' '${escaped}'"
}

function Get-SlurmQueue {
    param([hashtable]$Cfg)
    Invoke-SlurmSsh -Cfg $Cfg -RemoteCommand 'squeue -u $USER -h' 2>$null
}

function Wait-JenkinsBuild {
    param(
        [hashtable]$Jenkins,
        [hashtable]$Cfg,
        [string]$JobPath,
        [int]$BuildNumber,
        [int]$PollSeconds = 5,
        [int]$MaxPolls = 180
    )
    for ($i = 0; $i -lt $MaxPolls; $i++) {
        $b = Get-BuildStatus -Jenkins $Jenkins -JobPath $JobPath -BuildNumber $BuildNumber
        $slurm = Get-SlurmQueue -Cfg $Cfg
        $tail = Get-BuildConsoleTail -Jenkins $Jenkins -JobPath $JobPath -BuildNumber $BuildNumber -Lines 15
        Write-Host "===== poll $i $(Get-Date -Format HH:mm:ss) #$($b.number) building=$($b.building) result=$($b.result) ====="
        if ($slurm) { $slurm -split "`n" | ForEach-Object { Write-Host "  slurm: $_" } }
        else { Write-Host '  slurm: (none)' }
        $tail | ForEach-Object { Write-Host "  $_" }
        if (-not $b.building) { return $b }
        Start-Sleep -Seconds $PollSeconds
    }
    throw "Timeout waiting for build #$BuildNumber"
}

function Remove-StaleSlurmNodes {
    param([hashtable]$Jenkins, [string]$CloudName)
    $groovy = @"
jenkins.model.Jenkins.get().nodes.findAll { it.name?.startsWith('$CloudName') }.each {
  jenkins.model.Jenkins.get().removeNode(it)
}
jenkins.model.Jenkins.get().save()
return 'ok'
"@
    Invoke-JenkinsScript -Jenkins $Jenkins -Groovy $groovy | Out-Null
}

function ConvertTo-JobPath {
    param([string]$JobFullName)
    $parts = $JobFullName -split '/'
    return ($parts | ForEach-Object { "job/$_" }) -join '/'
}
