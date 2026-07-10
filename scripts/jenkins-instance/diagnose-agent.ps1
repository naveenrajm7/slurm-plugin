# Diagnose offline Slurm agents: Jenkins computer state, launch log, Slurm job.
param([string]$AgentName)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$cloudName = $c['SLURM_CLOUD_NAME']

if (-not $AgentName) {
    $groovy = @"
import jenkins.model.Jenkins
import io.jenkins.plugins.slurm.*
def nodes = Jenkins.get().nodes.findAll { it.name?.contains('$cloudName') }
return nodes.collect { n ->
  def comp = n.toComputer()
  def a = n instanceof SlurmAgent ? (SlurmAgent)n : null
  "${n.name}|online=${comp?.online}|connecting=${comp?.connecting}|offline=${comp?.offline}|numExecutors=${n.numExecutors}|desc=${n.nodeDescription?.take(120)}"
}.join('\n') ?: 'no agents'
"@
    Write-Host "=== Slurm agents on Jenkins ==="
    Invoke-JenkinsScript -Jenkins $j -Groovy $groovy
    $AgentName = (Invoke-JenkinsScript -Jenkins $j -Groovy "return jenkins.model.Jenkins.get().nodes.findAll{it.name?.contains('$cloudName')}.max{it.name}?.name") -replace 'Result:\s*', ''
    if ($AgentName) { Write-Host "Using latest agent: $AgentName" }
}

if ($AgentName) {
    $enc = [uri]::EscapeDataString($AgentName)
    Write-Host "`n=== Computer API: $AgentName ==="
    try {
        $comp = Invoke-JenkinsApi -Jenkins $j -Uri "$($j.BaseUrl)/computer/$enc/api/json?tree=displayName,offline,offlineCauseReason,temporarilyOffline,numExecutors,busyExecutors,idle"
        $comp | ConvertTo-Json -Depth 4
    } catch { Write-Host "computer api: $_" }

    Write-Host "`n=== Agent launch log (tail) ==="
    try {
        $log = Invoke-JenkinsApi -Jenkins $j -Uri "$($j.BaseUrl)/computer/$enc/logText/progressiveText?start=0"
        if ($log -is [byte[]]) { $log = [Text.Encoding]::UTF8.GetString($log) }
        "$log" -split "`n" | Select-Object -Last 40 | ForEach-Object { Write-Host $_ }
    } catch { Write-Host "log: $_" }

    $groovy = @"
import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins
def n = Jenkins.get().getNode('$AgentName')
if (!(n instanceof SlurmAgent)) return 'not a SlurmAgent'
def a = (SlurmAgent)n
return "jobId=${a.slurmJobId} partition=${a.partition} nodeList=${a.nodeList} cloud=${a.cloudName} template=${a.templateId}"
"@
    Write-Host "`n=== SlurmAgent fields ==="
    Invoke-JenkinsScript -Jenkins $j -Groovy $groovy
}

Write-Host "`n=== Slurm queue (user) ==="
Invoke-SlurmSsh -Cfg $c -RemoteCommand 'squeue -u $USER -o "%.18i %.9P %.30j %.8T %.10M %R" 2>/dev/null | head -20'
