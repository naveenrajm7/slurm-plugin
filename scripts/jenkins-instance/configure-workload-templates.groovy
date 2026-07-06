// Applied by configure-workload-templates.ps1 — production-style Slurm templates (native agent, no Pyxis).
// Merges into existing cloud; removes prior workload/smoke template names only.

import io.jenkins.plugins.slurm.*
import jenkins.model.Jenkins

def cloudName = '${SLURM_CLOUD_NAME}'
def agentUrl = '${JENKINS_AGENT_URL}'
def workdir = '${WORKLOAD_WORKDIR}'
def timeLimit = ${WORKLOAD_TIME_LIMIT_MINUTES} as Integer
def javaPath = '${WORKLOAD_JAVA_PATH}'
def jarPath = '${WORKLOAD_AGENT_JAR}'
def downloadJar = '${WORKLOAD_DOWNLOAD_AGENT_JAR}' == 'true'

def nogpuPartition = '${WORKLOAD_NOGPU_PARTITION}'
def nogpuCpus = ${WORKLOAD_NOGPU_CPUS} as Integer
def nogpuMem = ${WORKLOAD_NOGPU_MEMORY_MB} as Long

def gfx942Partition = '${WORKLOAD_GFX942_PARTITION}'
def gfx942Tres = '${WORKLOAD_GFX942_TRES}'
def gfx942Cpus = ${WORKLOAD_GFX942_CPUS} as Integer
def gfx942Mem = ${WORKLOAD_GFX942_MEMORY_MB} as Long

def gfx950Partition = '${WORKLOAD_GFX950_PARTITION}'
def gfx950Tres = '${WORKLOAD_GFX950_TRES}'
def gfx950Cpus = ${WORKLOAD_GFX950_CPUS} as Integer
def gfx950Mem = ${WORKLOAD_GFX950_MEMORY_MB} as Long

def gfx90aPartition = '${WORKLOAD_GFX90A_PARTITION}'
def gfx90aTres = '${WORKLOAD_GFX90A_TRES}'
def gfx90aCpus = ${WORKLOAD_GFX90A_CPUS} as Integer
def gfx90aMem = ${WORKLOAD_GFX90A_MEMORY_MB} as Long

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
  [name: 'wl-nogpu', label: 'rocmtest nogpu', partition: nogpuPartition, cpus: nogpuCpus, mem: nogpuMem, tres: null],
  [name: 'wl-gfx942', label: 'rocmtest gfx942', partition: gfx942Partition, cpus: gfx942Cpus, mem: gfx942Mem, tres: gfx942Tres],
  [name: 'wl-gfx950', label: 'rocmtest gfx950', partition: gfx950Partition, cpus: gfx950Cpus, mem: gfx950Mem, tres: gfx950Tres],
  [name: 'wl-gfx90a', label: 'rocmtest gfx90a', partition: gfx90aPartition, cpus: gfx90aCpus, mem: gfx90aMem, tres: gfx90aTres],
]

def managedNames = [
  'wl-nogpu', 'wl-gfx942', 'wl-gfx950', 'wl-gfx90a',
  'smoke-nogpu', 'smoke-gfx942', 'smoke-gfx950',
  'ck-nogpu', 'ck-gfx942', 'ck-gfx950',
] as Set

def templates = cloud.getJobTemplates()
templates.removeAll { t -> managedNames.contains(t.name) }

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
return 'workload-templates-ok'
