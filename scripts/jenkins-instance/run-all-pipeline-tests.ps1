# Build, deploy, configure all Slurm pipeline smoke jobs, and run them sequentially.
#
# Usage:
#   pwsh -File scripts/jenkins-instance/run-all-pipeline-tests.ps1
#   pwsh -File scripts/jenkins-instance/run-all-pipeline-tests.ps1 -SkipBuild -SkipDeploy

param(
    [switch]$SkipBuild,
    [switch]$SkipDeploy,
    [switch]$SkipConfigure
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$base = $cfg['JENKINS_URL']
$user = $cfg['JENKINS_ADMIN_USER']
$pass = $cfg['JENKINS_ADMIN_PASSWORD']
$cloud = $cfg['SLURM_CLOUD_NAME']
$partition = if ($cfg['SMOKE_PARTITION']) { $cfg['SMOKE_PARTITION'] } else { 'defq' }
$workdir = if ($cfg['SMOKE_WORKDIR']) { $cfg['SMOKE_WORKDIR'] } else { '/tmp/jenkins' }
$timeLimit = if ($cfg['SMOKE_TIME_LIMIT_MINUTES']) { $cfg['SMOKE_TIME_LIMIT_MINUTES'] } else { '60' }
$instanceE2e = if ($cfg['INSTANCE_E2E_FOLDER']) { $cfg['INSTANCE_E2E_FOLDER'] } else { 'slurm-instance-e2e' }
$ckFolder = if ($cfg['CK_SMOKE_FOLDER']) { $cfg['CK_SMOKE_FOLDER'] } else { 'slurm-ck-smoke' }
$ckJob = if ($cfg['CK_SMOKE_JOB']) { $cfg['CK_SMOKE_JOB'] } else { 'ck-smoke' }
$nogpuLabel = $cfg['SMOKE_NOGPU_LABEL']
if (-not $nogpuLabel) {
    throw 'Set SMOKE_NOGPU_LABEL in scripts/jenkins-instance/config.env (e.g. "(rocmtest || miopen) && nogpu")'
}

function Get-JenkinsSessionFromConfig {
    if ($pass -and $pass -ne 'unused') {
        return Get-JenkinsSession -BaseUrl $base -User $user -Password $pass
    }
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $crumb = Invoke-RestMethod -Uri "$base/crumbIssuer/api/json" -WebSession $session
    $headers = @{}
    $headers[$crumb.crumbRequestField] = $crumb.crumb
    return @{ Session = $session; Headers = $headers; BaseUrl = $base.TrimEnd('/') }
}

Write-Host '=== Run all Slurm pipeline tests ==='
Write-Host "Jenkins: $base"
Write-Host "Cloud: $cloud"

if (-not $SkipBuild) {
    Write-Host '=== mvn clean package ==='
    Push-Location (Resolve-Path "$PSScriptRoot\..\..")
    try {
        & mvn -ntp -q clean package
        if ($LASTEXITCODE -ne 0) { throw 'mvn package failed' }
    } finally { Pop-Location }
    Write-Host 'Build: OK'
}

if (-not $SkipDeploy) {
    & "$PSScriptRoot\deploy.ps1" -SkipBuild
}

if (-not (Test-JenkinsUp -BaseUrl $base)) {
    throw "Jenkins not reachable at $base"
}

$jenkins = Get-JenkinsSessionFromConfig
if (-not (Test-SlurmPluginLoaded -Jenkins $jenkins)) {
    throw 'Slurm plugin not loaded'
}

if (-not $SkipConfigure) {
    Write-Host '=== Configure label smoke ==='
    & "$PSScriptRoot\configure-smoke.ps1"
    Write-Host '=== Configure legato template ==='
    & "$PSScriptRoot\configure-legato-template.ps1"
    Write-Host '=== Configure instance-e2e jobs (template + declarative JSON) ==='
    $jenkins = Get-JenkinsSessionFromConfig

    $templateTest = @"
// Static cloud template test: provisions via Jenkins label (no inline slurmJobTemplate).
pipeline {
    agent { label '$nogpuLabel' }
    stages {
        stage('Smoke') {
            steps {
                sh 'hostname'
                sh 'nproc || true'
                sh 'sleep 10'
                sh 'pwd'
            }
        }
    }
}
"@

    $decJsonTest = @"
// Declarative slurm {} agent test (native agent, no Pyxis).
pipeline {
    agent none
    stages {
        stage('CPU Task') {
            agent {
                slurm {
                    cloud '$cloud'
                    label 'declarative-json-smoke'
                    json '''
                    {
                        "job": {
                            "partition": "$partition",
                            "cpus_per_task": 4,
                            "memory_per_node": 8192,
                            "current_working_directory": "$workdir",
                            "time_limit": $timeLimit
                        },
                        "agent": {
                            "java_path": "java",
                            "download_jar": true
                        }
                    }
                    '''
                }
            }
            steps {
                sh 'hostname'
                sh 'nproc'
                sh 'sleep 10'
                sh 'pwd'
            }
        }
    }
}
"@

    foreach ($pair in @(
            @{ Folder = $instanceE2e; Name = 'template-test'; Script = $templateTest },
            @{ Folder = $instanceE2e; Name = 'dec-json-test'; Script = $decJsonTest }
        )) {
        $folder = $pair.Folder
        $jobName = $pair.Name
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair.Script))
        $jobGroovy = @"
import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import java.util.Base64

def j = Jenkins.get()
def folder = j.getItem('$folder') as Folder
if (!folder) {
  folder = new Folder(j, '$folder')
  j.add(folder, '$folder')
}
def job = folder.getItem('$jobName') as WorkflowJob
if (!job) {
  job = new WorkflowJob(folder, '$jobName')
  folder.add(job, '$jobName')
}
def script = new String(Base64.decoder.decode('$b64'), 'UTF-8')
job.setDefinition(new CpsFlowDefinition(script, true))
job.save()
j.save()
return 'job-$jobName-ok'
"@
        Invoke-JenkinsScript -Jenkins $jenkins -Groovy $jobGroovy | Out-Null
        Write-Host "  Updated $folder/$jobName"
    }
    $jenkins = Get-JenkinsSessionFromConfig
}

Remove-StaleSlurmNodes -Jenkins $jenkins -CloudName $cloud

$jobs = @(
    "$instanceE2e/template-test",
    "$instanceE2e/dec-json-test",
    "$($cfg['E2E_FOLDER'])/$($cfg['E2E_JOB_TEMPLATE_TEST'])",
    "$($cfg['LEGATO_FOLDER'])/$($cfg['LEGATO_JOB'])"
)

# Legacy job name from earlier harness iterations.
if ($cfg['E2E_FOLDER'] -ne $ckFolder) {
    $jobs += "$ckFolder/$ckJob"
}

$failed = @()
$passed = @()

foreach ($jobFull in $jobs) {
    if ([string]::IsNullOrWhiteSpace($jobFull) -or $jobFull -match '/$') { continue }
    $jobPath = ConvertTo-JobPath -JobFullName $jobFull
    try {
        Invoke-JenkinsApi -Jenkins $jenkins -Uri "$($jenkins.BaseUrl)/$jobPath/api/json?tree=name" | Out-Null
    } catch {
        Write-Host "SKIP $jobFull (not found on Jenkins)" -ForegroundColor Yellow
        continue
    }

    Write-Host "`n=== Trigger $jobFull ==="
    $maxPolls = if ($jobFull -match 'label-smoke|ck-smoke') { 240 } else { 180 }
    $build = Start-JenkinsBuild -Jenkins $jenkins -JobPath $jobPath
    Write-Host "Build #$($build.number) started"
    $final = Wait-JenkinsBuild -Jenkins $jenkins -Cfg $cfg -JobPath $jobPath -BuildNumber $build.number -MaxPolls $maxPolls
    if ($final.result -ne 'SUCCESS') {
        $failed += "$jobFull #$($build.number) => $($final.result)"
        Write-Host "FAILED: $($final.result)" -ForegroundColor Red
        Get-BuildConsoleTail -Jenkins $jenkins -JobPath $jobPath -BuildNumber $build.number -Lines 30 |
            ForEach-Object { Write-Host $_ }
    } else {
        $passed += "$jobFull #$($build.number)"
        Write-Host "PASSED" -ForegroundColor Green
    }
    Remove-StaleSlurmNodes -Jenkins $jenkins -CloudName $cloud
}

Write-Host "`n=== Summary ==="
Write-Host "Passed: $($passed.Count)"
$passed | ForEach-Object { Write-Host "  OK  $_" -ForegroundColor Green }
if ($failed.Count -gt 0) {
    Write-Host "Failed: $($failed.Count)"
    $failed | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
    exit 1
}
Write-Host 'All pipeline tests passed.'
exit 0
