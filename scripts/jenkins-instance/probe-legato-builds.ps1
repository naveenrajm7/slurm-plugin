$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$cloudName = $c['SLURM_CLOUD_NAME']
$legatoFolder = $c['LEGATO_FOLDER']
$legatoJob = $c['LEGATO_JOB']
if (-not $cloudName -or -not $legatoFolder -or -not $legatoJob) {
    throw 'Set SLURM_CLOUD_NAME, LEGATO_FOLDER, and LEGATO_JOB in config.env'
}
$jobPath = ConvertTo-JobPath -JobFullName "$legatoFolder/$legatoJob"

foreach ($n in 1, 2, 3) {
    Write-Host "=== Build #$n ==="
    try {
        $b = Get-BuildStatus -Jenkins $j -JobPath $jobPath -BuildNumber $n
        Write-Host "result=$($b.result) building=$($b.building) duration=$($b.duration)"
        Get-BuildConsoleTail -Jenkins $j -JobPath $jobPath -BuildNumber $n -Lines 60 | ForEach-Object { Write-Host $_ }
    } catch {
        Write-Host "build #$n : $_"
    }
    Write-Host ''
}

Write-Host '=== Jenkins Slurm nodes ==='
$nodeGroovy = @"
import jenkins.model.Jenkins
import io.jenkins.plugins.slurm.*
Jenkins.get().nodes.findAll { it.name?.startsWith('$cloudName') }.collect { n ->
  def c = n.toComputer()
  def a = n instanceof SlurmAgent ? (SlurmAgent)n : null
  "${n.name}|online=${c?.online}|offline=${c?.offline}|offlineCause=${c?.offlineCauseReason}|jobId=${a?.slurmJobId}|nodes=${a?.nodeList}"
}.join("\n") ?: "none"
"@
Invoke-JenkinsScript -Jenkins $j -Groovy $nodeGroovy
