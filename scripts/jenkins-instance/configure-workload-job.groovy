// Applied by configure-workload-job.ps1 — folder, ck shared library, multibranch pipeline job.

import com.cloudbees.hudson.plugins.folder.Folder
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.UserRemoteConfig
import jenkins.branch.BranchSource
import jenkins.model.Jenkins
import jenkins.plugins.git.GitSCMSource
import jenkins.plugins.git.traits.BranchDiscoveryTrait
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration
import org.jenkinsci.plugins.workflow.libs.SCMRetriever
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory

def folderName = '${WORKLOAD_FOLDER}'
def jobName = '${WORKLOAD_JOB}'
def gitUrl = '${WORKLOAD_GIT_URL}'
def gitBranch = '${WORKLOAD_GIT_BRANCH}'
def jenkinsfile = '${WORKLOAD_JENKINSFILE}'
def libName = '${WORKLOAD_LIBRARY_NAME}'
def libRepo = '${WORKLOAD_LIBRARY_REPO}'
def libBranch = '${WORKLOAD_LIBRARY_BRANCH}'
def libPath = '${WORKLOAD_LIBRARY_PATH}'

def j = Jenkins.get()

if (!j.getItem(folderName)) {
  j.createProject(Folder.class, folderName)
}

def libScm = new GitSCM(
  [new UserRemoteConfig(libRepo, null, null, null)],
  [new BranchSpec("*/${libBranch}")],
  null,
  null,
  []
)
def libRetriever = new SCMRetriever(libScm)
libRetriever.setLibraryPath(libPath)

def libConfig = new LibraryConfiguration(libName, libRetriever)
libConfig.setDefaultVersion(libBranch)
libConfig.setImplicit(false)
libConfig.setAllowVersionOverride(true)
libConfig.setIncludeInChangesets(false)

def globalLibs = GlobalLibraries.get()
def libs = globalLibs.getLibraries()
libs.removeAll { it.name == libName }
libs.add(libConfig)
globalLibs.setLibraries(libs)

def folder = j.getItem(folderName) as Folder
def gitSource = new GitSCMSource('workload-rocm-libraries', gitUrl, '', gitBranch, '', false)
gitSource.setTraits([new BranchDiscoveryTrait()])

def mb = folder.getItem(jobName)
if (!(mb instanceof WorkflowMultiBranchProject)) {
  folder.getItems().findAll { it.name == jobName }.each { it.delete() }
  mb = new WorkflowMultiBranchProject(folder, jobName)
  folder.add(mb, jobName)
}

def factory = mb.getProjectFactory()
if (!(factory instanceof WorkflowBranchProjectFactory)) {
  factory = new WorkflowBranchProjectFactory()
}
factory.setScriptPath(jenkinsfile)
mb.setProjectFactory(factory)
mb.setSourcesList([new BranchSource(gitSource)])
mb.save()

j.save()
return 'workload-multibranch-ok'
