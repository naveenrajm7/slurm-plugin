# Jenkins REST helpers for Slurm plugin e2e tests (Windows / PowerShell).
# Dot-source: . "$PSScriptRoot\jenkins-api.ps1"

function Get-E2EConfig {
    param([string]$ConfigPath = "$PSScriptRoot\config.env")
    if (-not (Test-Path $ConfigPath)) {
        throw @"
Missing $ConfigPath
Copy scripts/e2e/config.env.example to scripts/e2e/config.env and set your values.
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
    if (-not $cfg['JENKINS_URL']) { $cfg['JENKINS_URL'] = 'http://localhost:8080/jenkins' }
    return $cfg
}

function Assert-E2EConfig {
    param([hashtable]$Cfg)
    $required = @('SLURM_SSH_HOST', 'SLURM_CLOUD_NAME', 'E2E_FOLDER')
    foreach ($key in $required) {
        $val = $Cfg[$key]
        if ([string]::IsNullOrWhiteSpace($val)) {
            throw "Set $key in scripts/e2e/config.env"
        }
        if ($val -match '^(YOUR_|your-)') {
            throw "Replace placeholder value for $key in scripts/e2e/config.env"
        }
    }
}

function Get-JenkinsSession {
    param([string]$BaseUrl)
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $crumb = Invoke-RestMethod -Uri "$BaseUrl/crumbIssuer/api/json" -WebSession $session
    $headers = @{ $crumb.crumbRequestField = $crumb.crumb }
    return @{ Session = $session; Headers = $headers; BaseUrl = $BaseUrl }
}

function Invoke-JenkinsScript {
    param([hashtable]$Jenkins, [string]$Groovy)
    $body = "script=$([uri]::EscapeDataString($Groovy))"
    Invoke-RestMethod -Uri "$($Jenkins.BaseUrl)/scriptText" -Method Post `
        -Headers $Jenkins.Headers -Body $body `
        -ContentType 'application/x-www-form-urlencoded' `
        -WebSession $Jenkins.Session -TimeoutSec 120
}

function Start-JenkinsBuild {
    param([hashtable]$Jenkins, [string]$JobPath)
    Invoke-WebRequest -Uri "$($Jenkins.BaseUrl)/$JobPath/build?delay=0" -Method Post `
        -Headers $Jenkins.Headers -WebSession $Jenkins.Session -UseBasicParsing | Out-Null
    Start-Sleep -Seconds 2
    return Invoke-RestMethod -Uri "$($Jenkins.BaseUrl)/$JobPath/lastBuild/api/json?tree=number,building,url,result"
}

function Get-BuildStatus {
    param([hashtable]$Jenkins, [string]$JobPath, [int]$BuildNumber)
    Invoke-RestMethod -Uri "$($Jenkins.BaseUrl)/$JobPath/$BuildNumber/api/json?tree=number,building,result,duration,url"
}

function Get-BuildConsoleTail {
    param([hashtable]$Jenkins, [string]$JobPath, [int]$BuildNumber, [int]$Lines = 25)
    $text = (Invoke-WebRequest -Uri "$($Jenkins.BaseUrl)/$JobPath/$BuildNumber/consoleText" -UseBasicParsing).Content
    return ($text -split "`n" | Select-Object -Last $Lines)
}

function Invoke-SlurmSsh {
    param([hashtable]$Cfg, [string]$RemoteCommand)
    $sshHost = $Cfg['SLURM_SSH_HOST']
    wsl -e ssh -o BatchMode=yes -o ConnectTimeout=10 $sshHost $RemoteCommand
}

function Get-SlurmQueue {
    param([hashtable]$Cfg)
    Invoke-SlurmSsh -Cfg $Cfg -RemoteCommand 'squeue -u $USER -h' 2>$null
}

function Test-JenkinsUp {
    param([string]$BaseUrl)
    try {
        return (Invoke-WebRequest -Uri "$BaseUrl/api/json" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200
    } catch { return $false }
}

function Test-AgentTunnel {
    param([hashtable]$Cfg)
    $port = if ($Cfg['TUNNEL_REMOTE_PORT']) { $Cfg['TUNNEL_REMOTE_PORT'] } else { 5000 }
    $code = Invoke-SlurmSsh -Cfg $Cfg -RemoteCommand "curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:${port}/jenkins/"
    return "$code".Trim() -eq '200'
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

function Set-TemplateWorkdir {
    param([hashtable]$Jenkins, [string]$Label, [string]$Workdir)
    $groovy = @"
import io.jenkins.plugins.slurm.SlurmCloud
def c = jenkins.model.Jenkins.get().clouds.find { it instanceof SlurmCloud }
def t = c.templates.find { it.label == '$Label' }
t.setCurrentWorkingDirectory('$Workdir')
jenkins.model.Jenkins.get().save()
return t.currentWorkingDirectory
"@
    Invoke-JenkinsScript -Jenkins $Jenkins -Groovy $groovy
}

function ConvertTo-JobPath {
    param([string]$JobFullName)
    $parts = $JobFullName -split '/'
    return ($parts | ForEach-Object { "job/$_" }) -join '/'
}
