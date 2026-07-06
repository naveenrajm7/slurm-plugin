// Applied by configure-smoke.ps1 — CK-style label smoke templates (native agent, no Pyxis).
// Placeholders expanded from config.env at configure time.

import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins

def cloudName = '${SLURM_CLOUD_NAME}'
def agentUrl = '${JENKINS_AGENT_URL}'
def workdir = '${CK_SMOKE_WORKDIR}'
def nogpuPartition = '${CK_SMOKE_PARTITION}'
def gpuTemplateName = '${CK_GPU_TEMPLATE_NAME}'
def gpuLabel = '${CK_GPU_LABEL}'
def gpuPartition = '${CK_GPU_PARTITION}'
def gpuTres = '${CK_GPU_TRES}'
def gpuCpus = ${CK_GPU_CPUS} as Integer
def gpuMem = ${CK_GPU_MEMORY_MB} as Long
def timeLimit = ${CK_SMOKE_TIME_LIMIT_MINUTES} as Integer
def javaPath = '${CK_NATIVE_JAVA_PATH}'
def jarPath = '${CK_NATIVE_AGENT_JAR}'
def downloadJar = '${CK_NATIVE_DOWNLOAD_JAR}' == 'true'

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
  [name: 'ck-nogpu', label: 'rocmtest nogpu', partition: nogpuPartition, cpus: 4, mem: 8192L, tres: null],
  [name: gpuTemplateName, label: gpuLabel, partition: gpuPartition, cpus: gpuCpus, mem: gpuMem, tres: gpuTres],
]

def templates = cloud.getJobTemplates()
def smokeNames = ['ck-nogpu', 'ck-gfx942', 'ck-gfx950'] as Set
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
