// Applied by configure-smoke.ps1 — label smoke templates (native agent, no Pyxis).
// Placeholders expanded from config.env at configure time.

import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins

def cloudName = '${SLURM_CLOUD_NAME}'
def agentUrl = '${JENKINS_AGENT_URL}'
def workdir = '${SMOKE_WORKDIR}'
def nogpuPartition = '${SMOKE_PARTITION}'
def gpuTemplateName = '${SMOKE_GPU_TEMPLATE_NAME}'
def gpuLabel = '${SMOKE_GPU_LABEL}'
def gpuPartition = '${SMOKE_GPU_PARTITION}'
def gpuTres = '${SMOKE_GPU_TRES}'
def gpuCpus = ${SMOKE_GPU_CPUS} as Integer
def gpuMem = ${SMOKE_GPU_MEMORY_MB} as Long
def timeLimit = ${SMOKE_TIME_LIMIT_MINUTES} as Integer
def javaPath = '${SMOKE_JAVA_PATH}'
def jarPath = '${SMOKE_AGENT_JAR}'
def downloadJar = '${SMOKE_DOWNLOAD_AGENT_JAR}' == 'true'

def j = Jenkins.get()
def cloud = j.clouds.find { it instanceof SlurmCloud && it.name == cloudName } as SlurmCloud
if (!cloud) {
  throw new IllegalStateException("Slurm cloud not found: ${cloudName}")
}

def agent = new AgentLaunchConfig()
agent.setJavaPath(javaPath)
if (downloadJar) {
  agent.setDownloadJar(true)
} else {
  agent.setJarPath(jarPath)
}
cloud.setAgent(agent)
cloud.setJenkinsUrl(agentUrl)

def specs = [
  [name: 'smoke-nogpu', label: 'rocmtest nogpu', partition: nogpuPartition, cpus: 4, mem: 8192L, tres: null],
  [name: gpuTemplateName, label: gpuLabel, partition: gpuPartition, cpus: gpuCpus, mem: gpuMem, tres: gpuTres],
]

def templates = cloud.getJobTemplates()
def smokeNames = [
  'smoke-nogpu', 'smoke-gfx942', 'smoke-gfx950',
  'ck-nogpu', 'ck-gfx942', 'ck-gfx950',
] as Set
templates.removeAll { t -> smokeNames.contains(t.name) }

specs.each { spec ->
  def t = new SlurmJobTemplate()
  t.setName(spec.name)
  t.setLabel(spec.label)
  t.setPartition(spec.partition)
  t.setCpusPerTask(spec.cpus as Integer)
  t.setMemoryPerNode(spec.mem as Long)
  t.setCurrentWorkingDirectory(workdir)
  t.setTimeLimit(timeLimit)
  t.setRunOnce(true)
  t.setIdleMinutes(0)
  if (spec.tres) {
    t.setTresPerJob(spec.tres)
  }
  def tAgent = new AgentLaunchConfig()
  tAgent.setJavaPath(javaPath)
  if (downloadJar) {
    tAgent.setDownloadJar(true)
  } else {
    tAgent.setJarPath(jarPath)
  }
  t.setAgent(tAgent)
  templates.add(t)
}

cloud.setJobTemplates(templates)
j.save()
return 'smoke-templates-ok'
