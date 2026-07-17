// Applied by configure-legato-template.ps1 — CPU builder template mimicking a static legato node.
// Slurm: 1 job = 1 agent = 1 executor; NUMBER_OF_EXECUTORS=1 is set for pipeline compatibility.
// Omits DOCKER_GPU_MASK_* (static multi-executor GPU masking not needed on Slurm).

import io.jenkins.plugins.slurm.*
import hudson.model.Node
import jenkins.model.Jenkins

def cloudName = '${SLURM_CLOUD_NAME}'
def agentUrl = '${JENKINS_AGENT_URL}'
def templateName = '${LEGATO_TEMPLATE_NAME}'
def label = '${LEGATO_LABEL}'
def partition = '${LEGATO_PARTITION}'
def workdir = '${LEGATO_WORKDIR}'
def cpus = ${LEGATO_CPUS} as Integer
def mem = ${LEGATO_MEMORY_MB} as Long
def timeLimit = ${LEGATO_TIME_LIMIT_MINUTES} as Integer
def javaPath = '${LEGATO_JAVA_PATH}'
def jarPath = '${LEGATO_AGENT_JAR}'
def downloadJar = '${LEGATO_DOWNLOAD_AGENT_JAR}' == 'true'
def envJson = '${LEGATO_ENVIRONMENT_JSON}'

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

def gc = new SlurmGarbageCollection()
gc.setTimeoutSeconds(600)
cloud.setGarbageCollection(gc)

def templates = cloud.getJobTemplates()
templates.removeAll { it.name == templateName }

def t = new SlurmJobTemplate()
t.setName(templateName)
t.setLabel(label)
t.setPartition(partition)
t.setCpusPerTask(cpus)
t.setMemoryPerNode(mem)
t.setCurrentWorkingDirectory(workdir)
t.setTimeLimit(timeLimit)
t.setNodeUsageMode(Node.Mode.NORMAL)
t.setRunOnce(true)
t.setIdleMinutes(5)
t.setEnvironment(envJson)

def tAgent = new AgentLaunchConfig()
tAgent.setJavaPath(javaPath)
if (downloadJar) {
  tAgent.setDownloadJar(true)
} else {
  tAgent.setJarPath(jarPath)
}
t.setAgent(tAgent)
templates.add(t)

cloud.setJobTemplates(templates)
j.save()
return "legato-template-ok name=${templateName} label=[${label}] workdir=${workdir} env=${envJson}"
