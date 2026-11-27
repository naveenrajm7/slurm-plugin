# Live check for issue 68: an agent that is only temporarily offline (disk space monitor)
# must keep its RUNNING Slurm job and finish its build.
#
# 1. Runs a pipeline on a real Slurm agent (template label below) that sleeps for -BuildSeconds.
# 2. Once the build is on the agent, marks the computer temporarily offline with the same
#    DiskSpace cause the disk space monitor uses.
# 3. For -WatchSeconds (past the reaper grace, a reaper cycle, and the GC timeout if it is short)
#    the node must stay registered, the Slurm job RUNNING, and the build running.
# 4. The build must finish SUCCESS, then the run-once agent is removed and its job ends.
# Refuses to run if the matching template already has an agent. Only touches the agent that runs
# its own build, and cleans up that build, node, and job on failure.

param(
    [string]$CloudName = '',
    [string]$Label = 'rocmtest && nogpu',
    [int]$BuildSeconds = 480,
    [int]$WatchSeconds = 330,
    [string]$JobName = 'slurm-disk-space-offline'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\jenkins-api.ps1"

$cfg = Get-InstanceConfig
$cloudName = if ($CloudName) { $CloudName } else { $cfg['SLURM_CLOUD_NAME'] }
$ticks = [int][math]::Ceiling($BuildSeconds / 10)
# A federated job reads as PENDING on the origin cluster while it runs on a sibling.
$active = @('RUNNING', 'PENDING')

function Invoke-Console([string]$Groovy) {
    $j = Connect-JenkinsScriptConsole -Cfg $cfg
    $body = "script=$([uri]::EscapeDataString($Groovy))"
    $out = Invoke-RestMethod -Uri "$($j.BaseUrl)/scriptText" -Method Post -Headers $j.Headers `
        -WebSession $j.Session -Body $body -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 120
    return ("$out".Trim() -replace '^Result:\s*', '')
}

$header = @"
import hudson.model.*
import hudson.node_monitors.DiskSpaceMonitorDescriptor
import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def j = Jenkins.get()
def cloud = j.clouds.find { it instanceof SlurmCloud && it.name == '$cloudName' } as SlurmCloud
def slurmState = { String id ->
    if (!id) { return 'n/a' }
    def fetch = SlurmCloud.getDeclaredMethod('fetchJobStatus', String)
    fetch.accessible = true
    def s = fetch.invoke(cloud, id)
    return s == null || s.missing ? 'missing' : (s.state ?: 'PENDING')
}
def agentFor = { Run run ->
    j.nodes.findAll { it instanceof SlurmAgent }.find { a ->
        a.toComputer()?.executors?.any { e -> e.currentExecutable?.parentExecutable == run }
    } as SlurmAgent
}
"@

$setup = @"
$header
if (cloud == null) { return 'FAIL cloud not found' }
def template = cloud.jobTemplates.find { t -> Label.parseExpression('$Label').matches(Label.parse(t.label)) }
if (template == null) { return 'FAIL no template matches $Label' }
def others = j.nodes.findAll { it instanceof SlurmAgent && it.templateId == template.id }.collect { it.nodeName }
if (others) { return 'FAIL template ' + template.name + ' already has agents: ' + others.join(', ') }
def job = j.getItem('$JobName') as WorkflowJob
if (job == null) { job = j.createProject(WorkflowJob, '$JobName') }
job.definition = new CpsFlowDefinition('''
node('$Label') {
    sh 'hostname; for i in `$(seq 1 $ticks); do echo "tick `$i"; sleep 10; done'
}
''', true)
job.save()
def gc = cloud.garbageCollection
def run = job.scheduleBuild2(0).waitForStart()
return 'build=' + run.number + ' template=' + template.name + ' plugin=' + j.pluginManager.getPlugin('slurm').version +
        ' gcTimeout=' + (gc == null ? 'off' : gc.durationTimeout.seconds)
"@

function Get-Probe([int]$Build, [string]$Mark) {
    Invoke-Console @"
$header
def run = (j.getItem('$JobName') as WorkflowJob).getBuildByNumber($Build)
def agent = agentFor(run)
def name = agent?.nodeName ?: '$script:AgentName'
def node = name ? j.getNode(name) as SlurmAgent : null
def c = node?.toComputer()
def jobId = node?.slurmJobId ?: '$script:SlurmJobId'
if ('$Mark' == 'mark' && c != null && !c.temporarilyOffline) {
    c.setTemporarilyOffline(true, new DiskSpaceMonitorDescriptor.DiskSpace('/tmp/jenkins', 1L))
}
return 'agent=' + (name ?: '') + ' node=' + (node != null) + ' job=' + (jobId ?: '') +
        ' slurm=' + slurmState(jobId) + ' connected=' + (c?.channel != null) +
        ' tempOffline=' + (c?.temporarilyOffline ?: false) + ' cause=' + (c?.offlineCauseReason ?: '').replace(' ', '_') +
        ' building=' + run.building + ' result=' + run.result
"@
}

function Get-Field([string]$Line, [string]$Name) {
    return ([regex]::Match($Line, "$Name=(\S*)")).Groups[1].Value
}

function Invoke-Cleanup([int]$Build) {
    Invoke-Console @"
$header
def run = (j.getItem('$JobName') as WorkflowJob)?.getBuildByNumber($Build)
if (run?.building) { run.doKill() }
def node = '$script:AgentName' ? j.getNode('$script:AgentName') : null
if ('$script:SlurmJobId') { cloud.cancelJob('$script:SlurmJobId', TaskListener.NULL) }
if (node != null) { j.removeNode(node) }
return 'cleanup build=$Build agent=$script:AgentName job=$script:SlurmJobId'
"@
}

$script:AgentName = ''
$script:SlurmJobId = ''
$failures = New-Object System.Collections.Generic.List[string]
$finished = $false
$started = "$(Invoke-Console $setup)"
Write-Host $started
if ($started -notmatch 'build=(\d+)') { throw "Setup failed: $started" }
$build = [int]$Matches[1]

try {
    Write-Host "1. Waiting for build #$build to run on a Slurm agent"
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt 900) {
        $line = Get-Probe $build ''
        if ((Get-Field $line 'agent') -and (Get-Field $line 'connected') -eq 'true') { break }
        if ((Get-Field $line 'building') -eq 'false') { throw "Build ended before it reached an agent: $line" }
        Start-Sleep -Seconds 10
    }
    $script:AgentName = Get-Field $line 'agent'
    $script:SlurmJobId = Get-Field $line 'job'
    if (-not $script:AgentName) { throw "Build never reached a Slurm agent: $line" }
    Write-Host "  $line"

    Write-Host "2. Marking $script:AgentName temporarily offline for disk space; watching $WatchSeconds s"
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt $WatchSeconds) {
        $line = Get-Probe $build 'mark'
        Write-Host ("  +{0,3}s  {1}" -f [int]((Get-Date) - $t0).TotalSeconds, $line)
        if ((Get-Field $line 'node') -ne 'true') { $failures.Add("node removed while temporarily offline: $line"); break }
        if ((Get-Field $line 'slurm') -notin $active) { $failures.Add("Slurm job is no longer active: $line"); break }
        if ((Get-Field $line 'building') -ne 'true') { $failures.Add("build stopped while temporarily offline: $line"); break }
        Start-Sleep -Seconds 15
    }
    if ($failures.Count -eq 0) { Write-Host "PASS node, Slurm job, and build survived the disk-space offline mark" }

    Write-Host "3. Waiting for the build to finish and the run-once agent to go away"
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt ($BuildSeconds + 300)) {
        $line = Get-Probe $build ''
        if ((Get-Field $line 'building') -eq 'false' -and (Get-Field $line 'node') -eq 'false' -and (Get-Field $line 'slurm') -ne 'RUNNING') { break }
        Start-Sleep -Seconds 15
    }
    Write-Host "  $line"
    if ((Get-Field $line 'result') -ne 'SUCCESS') { $failures.Add("build result was not SUCCESS: $line") }
    if ((Get-Field $line 'node') -ne 'false') { $failures.Add("agent still registered after the build: $line") }
    if ((Get-Field $line 'slurm') -eq 'RUNNING') { $failures.Add("Slurm job still RUNNING after the build: $line") }

    $console = Invoke-Console @"
$header
def log = (j.getItem('$JobName') as WorkflowJob).getBuildByNumber($build).log
return log.readLines().findAll { it.contains('[Slurm]') || it.contains('offline') || it.contains('tick $ticks') }.join('\n')
"@
    Write-Host "Build console ([Slurm], offline, last tick lines):"
    Write-Host $console
    if ($console -match 'stale agent') { $failures.Add('build console shows a reaper cancel') }
    $finished = $true
} finally {
    if (-not $finished -or $failures.Count -gt 0) { Write-Host (Invoke-Cleanup $build) }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL $_" }
    throw "Disk-space offline scenario failed"
}
Write-Host "RESULT pass: build #$build on $script:AgentName (Slurm job $script:SlurmJobId) finished while temporarily offline"
