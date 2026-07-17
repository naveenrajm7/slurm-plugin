$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"
$c = Get-InstanceConfig
$j = Connect-JenkinsScriptConsole -Cfg $c
$jobId = if ($args[0]) { $args[0] } elseif ($c['PROBE_SLURM_JOB_ID']) { $c['PROBE_SLURM_JOB_ID'] } else {
    throw 'Usage: probe-slurm-job-status.ps1 <slurm-job-id>  (or set PROBE_SLURM_JOB_ID in config.env)'
}

Write-Host "=== REST job status via plugin (job $jobId) ==="
$groovy = @"
import io.jenkins.plugins.slurm.*
import io.jenkins.plugins.slurm.client.*
def cloud = Jenkins.get().clouds.find { it instanceof SlurmCloud && it.name == '${c['SLURM_CLOUD_NAME']}' } as SlurmCloud
def client = SlurmClientProvider.createClient(cloud)
try {
  def status = client.getJobStatus('$jobId')
  return status == null ? 'getJobStatus=null (plugin treats as missing/cancelled)' : status.toString()
} catch (Exception e) {
  return 'exception=' + e.class.name + ': ' + e.message
}
"@
Invoke-JenkinsScript -Jenkins $j -Groovy $groovy

Write-Host "`n=== Raw slurmrestd GET (login node) ==="
$rest = $c['SLURM_REST_URL']
$cmd = "curl -sS -H 'X-Slurm-USER-TOKEN: \$(scontrol token lifespan=3600 | sed -n 's/^SLURM_JWT=//p')' '${rest}/slurm/v0.0.42/job/${jobId}' | head -c 4000"
Invoke-SlurmSsh -Cfg $c -RemoteCommand $cmd

Write-Host "`n=== scontrol show job ==="
Invoke-SlurmSsh -Cfg $c -RemoteCommand "scontrol show job $jobId | head -30"
