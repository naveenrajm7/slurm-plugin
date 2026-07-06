# Legacy poll helper — prefer run-smoke.ps1 (uses config.env throughout).
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$base = $cfg['JENKINS_URL']
$folder = $cfg['E2E_FOLDER']
$jobName = $cfg['E2E_JOB_TEMPLATE_TEST']
$jobPath = (ConvertTo-JobPath -JobFullName "$folder/$jobName")
$jenkins = Connect-JenkinsScriptConsole -Cfg $cfg

Write-Host "Triggering job/$jobPath ..."
$build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
Write-Host "Build #$($build.number) started: $($build.url)"

for ($i = 0; $i -lt 240; $i++) {
    Start-Sleep -Seconds 5
    $b = Get-BuildStatus -Jenkins $jenkins -JobPath $jobPath -BuildNumber $build.number
    $slurm = Invoke-SlurmSsh -Cfg $cfg -RemoteCommand 'squeue -u $USER -h 2>/dev/null | head -5' 2>$null
    $tail = Get-BuildConsoleTail -Jenkins $jenkins -JobPath $jobPath -BuildNumber $build.number -Lines 8
    Write-Host "===== poll $i #$($b.number) building=$($b.building) result=$($b.result) ====="
    if ($slurm) { "$slurm" -split "`n" | ForEach-Object { Write-Host "  slurm: $_" } } else { Write-Host "  slurm: (none)" }
    $tail | ForEach-Object { Write-Host $_ }
    if (-not $b.building) {
        Write-Host "=== Final: $($b.result) ==="
        if ($b.result -ne 'SUCCESS') { exit 1 }
        exit 0
    }
}
Write-Host "Timeout"
exit 1
