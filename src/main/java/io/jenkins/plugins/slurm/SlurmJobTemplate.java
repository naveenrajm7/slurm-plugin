package io.jenkins.plugins.slurm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.util.FormValidation;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpRedirect;
import jenkins.model.Jenkins;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Represents a Slurm job template that defines the parameters for submitting
 * jobs to a Slurm cluster. This template maps directly to Slurm's job_desc_msg structure
 * from the REST API, allowing users to define different job configurations for different
 * build requirements.
 * 
 * The template structure follows Slurm's v0.0.42_job_desc_msg to minimize processing
 * and allow future code-based template definitions to match the API structure.
 */
public class SlurmJobTemplate extends AbstractDescribableImpl<SlurmJobTemplate> {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmJobTemplate.class.getName());
    
    // Template metadata (not part of Slurm job submission)
    private final String id;
    private String name;
    private String label;
    private Node.Mode nodeUsageMode;
    private int instanceCap;
    private int idleMinutes;
    private boolean runOnce;  // If true, agent terminates after one build (default: true)
    private boolean keepJobOnFailure;  // If true, don't cancel Slurm job when build fails (for debugging)
    
    // Core Slurm job submission fields (maps to v0.0.42_job_desc_msg)
    @JsonProperty("partition")
    private String partition;                    // partition: which Slurm partition to use
    
    @JsonProperty("current_working_directory")
    private String currentWorkingDirectory;      // current_working_directory: where to run the job
    
    @JsonProperty("cpus_per_task")
    private Integer cpusPerTask;                 // cpus_per_task: CPUs per task
    
    @JsonProperty("memory_per_node")
    private Long memoryPerNode;                  // memory_per_node: memory in MB per node
    
    @JsonProperty("script")
    private String script;                       // script: batch script content (will contain Jenkins agent launcher)
    
    @JsonProperty("time_limit")
    private Integer timeLimit;                   // time_limit: max runtime in minutes
    
    // TRES (Trackable RESources) - for GPUs and other resources
    @JsonProperty("tres_per_job")
    private String tresPerJob;                   // tres_per_job: e.g., "gres/gpu:gfx942:1"
    
    @JsonProperty("tres_per_node")
    private String tresPerNode;                  // tres_per_node: TRES per node
    
    @JsonProperty("tres_per_task")
    private String tresPerTask;                  // tres_per_task: TRES per task
    
    // Additional optional fields
    @JsonProperty("minimum_nodes")
    private Integer minimumNodes;                // minimum_nodes: minimum node count (default 1)
    
    @JsonProperty("tasks")
    private Integer tasks;                       // tasks: number of tasks (default 1)
    
    @JsonProperty("account")
    private String account;                      // account: Slurm account to charge
    
    @JsonProperty("qos")
    private String qos;                          // qos: Quality of Service
    
    @JsonProperty("constraints")
    private String constraints;                  // constraints: required features (comma-separated)
    
    @JsonProperty("required_nodes")
    private String requiredNodes;                // required_nodes: specific nodes required (comma-separated)
    
    @JsonProperty("excluded_nodes")
    private String excludedNodes;                // excluded_nodes: nodes to exclude (comma-separated)
    
    @JsonProperty("environment")
    private String environment;                  // environment: environment variables (as JSON array string)
    
    // Phase 1: Essential fields for pipeline support
    // Job identification and features
    @JsonProperty("name")
    private String jobName;                      // name: Slurm job name (different from template name)
    
    @JsonProperty("comment")
    private String comment;                      // comment: user comment for the job
    
    @JsonProperty("prefer")
    private String prefer;                       // prefer: preferred but not required features
    
    @JsonProperty("reservation")
    private String reservation;                  // reservation: name of reservation to use
    
    @JsonProperty("network")
    private String network;                      // network: network specifications for job
    
    @JsonProperty("nice")
    private Integer nice;                        // nice: requested job priority change
    
    @JsonProperty("reboot")
    private Boolean reboot;                      // reboot: node reboot requested before start
    
    // Extended TRES fields
    @JsonProperty("tres_per_socket")
    private String tresPerSocket;                // tres_per_socket: TRES per socket
    
    @JsonProperty("tres_bind")
    private String tresBind;                     // tres_bind: task to TRES binding directives
    
    @JsonProperty("tres_freq")
    private String tresFreq;                     // tres_freq: TRES frequency directives
    
    // Time limits
    @JsonProperty("time_minimum")
    private Integer timeMinimum;                 // time_minimum: minimum time limit in minutes
    
    // Resource allocation - node specs
    @JsonProperty("nodes")
    private String nodes;                        // nodes: node count range (e.g., "1-15:4")
    
    @JsonProperty("maximum_nodes")
    private Integer maximumNodes;                // maximum_nodes: maximum node count
    
    @JsonProperty("minimum_cpus")
    private Integer minimumCpus;                 // minimum_cpus: minimum CPU count
    
    @JsonProperty("maximum_cpus")
    private Integer maximumCpus;                 // maximum_cpus: maximum CPU count
    
    // Resource allocation - per-node specs
    @JsonProperty("sockets_per_node")
    private Integer socketsPerNode;              // sockets_per_node: sockets per node
    
    @JsonProperty("threads_per_core")
    private Integer threadsPerCore;              // threads_per_core: threads per core
    
    @JsonProperty("tasks_per_node")
    private Integer tasksPerNode;                // tasks_per_node: tasks per node
    
    @JsonProperty("tasks_per_socket")
    private Integer tasksPerSocket;              // tasks_per_socket: tasks per socket
    
    @JsonProperty("tasks_per_core")
    private Integer tasksPerCore;                // tasks_per_core: tasks per core
    
    @JsonProperty("tasks_per_board")
    private Integer tasksPerBoard;               // tasks_per_board: tasks per board
    
    @JsonProperty("ntasks_per_tres")
    private Integer ntasksPerTres;               // ntasks_per_tres: tasks per TRES (e.g., per GPU)
    
    @JsonProperty("minimum_cpus_per_node")
    private Integer minimumCpusPerNode;          // minimum_cpus_per_node: min CPUs per node
    
    @JsonProperty("minimum_boards_per_node")
    private Integer minimumBoardsPerNode;        // minimum_boards_per_node: boards per node
    
    @JsonProperty("minimum_sockets_per_board")
    private Integer minimumSocketsPerBoard;      // minimum_sockets_per_board: sockets per board
    
    // Memory allocation
    @JsonProperty("memory_per_cpu")
    private Long memoryPerCpu;                   // memory_per_cpu: memory in MB per CPU
    
    @JsonProperty("temporary_disk_per_node")
    private Integer temporaryDiskPerNode;        // temporary_disk_per_node: tmp disk per node in MB
    
    // I/O redirection
    @JsonProperty("standard_output")
    private String standardOutput;               // standard_output: path to stdout file
    
    @JsonProperty("standard_error")
    private String standardError;                // standard_error: path to stderr file
    
    @JsonProperty("standard_input")
    private String standardInput;                // standard_input: path to stdin file
    
    // Container support (Pyxis/Enroot)
    private PyxisConfig pyxis;                   // Pyxis container configuration

    // Native agent launch (without Pyxis)
    private AgentLaunchConfig agent;
    
    // Pipeline build context (transient - not persisted)
    private transient TaskListener listener;     // Build's TaskListener for pipeline error reporting
    
    @DataBoundConstructor
    public SlurmJobTemplate() {
        this.id = UUID.randomUUID().toString();
        this.name = "default";
        this.label = "";
        this.nodeUsageMode = Node.Mode.EXCLUSIVE;
        this.instanceCap = 1;
        this.idleMinutes = 1;
        this.runOnce = true;  // Default: terminate after one build
        this.keepJobOnFailure = false;  // Default: always cancel job on termination
        
        // Slurm defaults (keeping 1 node, 1 task for Jenkins agent)
        this.partition = "";
        this.currentWorkingDirectory = "/tmp/jenkins";
        this.cpusPerTask = 1;
        this.memoryPerNode = 1024L;  // 1GB in MB
        this.script = "";
        this.timeLimit = 60;  // 60 minutes default
        this.minimumNodes = 1;
        this.tasks = 1;
        this.tresPerJob = "";
        this.tresPerNode = "";
        this.tresPerTask = "";
        this.account = "";
        this.qos = "";
        this.constraints = "";
        this.environment = "";
        
        // Phase 1 fields - initialize to null/empty (all optional)
        this.jobName = "";
        this.comment = "";
        this.prefer = "";
        this.reservation = "";
        this.network = "";
        this.nice = null;
        this.reboot = null;
        this.tresPerSocket = "";
        this.tresBind = "";
        this.tresFreq = "";
        this.timeMinimum = null;
        this.nodes = "";
        this.maximumNodes = null;
        this.minimumCpus = null;
        this.maximumCpus = null;
        this.socketsPerNode = null;
        this.threadsPerCore = null;
        this.tasksPerNode = null;
        this.tasksPerSocket = null;
        this.tasksPerCore = null;
        this.tasksPerBoard = null;
        this.ntasksPerTres = null;
        this.minimumCpusPerNode = null;
        this.minimumBoardsPerNode = null;
        this.minimumSocketsPerBoard = null;
        this.memoryPerCpu = null;
        this.temporaryDiskPerNode = null;
        this.standardOutput = "";
        this.standardError = "";
        this.standardInput = "";
        this.pyxis = null;
        this.agent = null;
    }
    
    public SlurmJobTemplate(String name, String label) {
        this();
        this.name = name;
        this.label = label;
    }
    
    // ====================
    // Template Metadata Getters/Setters
    // ====================
    
    public String getId() {
        return id;
    }
    
    @JsonIgnore  // This is the template name (UI), not the Slurm job name (REST API)
    public String getName() {
        return name;
    }
    
    @JsonIgnore  // This is the template name (UI), not the Slurm job name (REST API)
    @DataBoundSetter
    public void setName(String name) {
        this.name = name != null ? name : "default";
    }
    
    public String getLabel() {
        return label;
    }
    
    @DataBoundSetter
    public void setLabel(String label) {
        this.label = label != null ? label : "";
    }
    
    public Node.Mode getNodeUsageMode() {
        return nodeUsageMode;
    }
    
    @DataBoundSetter
    public void setNodeUsageMode(Node.Mode nodeUsageMode) {
        this.nodeUsageMode = nodeUsageMode;
    }
    
    public int getInstanceCap() {
        return instanceCap;
    }
    
    @DataBoundSetter
    public void setInstanceCap(int instanceCap) {
        this.instanceCap = instanceCap > 0 ? instanceCap : 1;
    }
    
    public int getIdleMinutes() {
        return idleMinutes;
    }
    
    @DataBoundSetter
    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes >= 0 ? idleMinutes : 1;
    }
    
    public boolean isRunOnce() {
        return runOnce;
    }
    
    @DataBoundSetter
    public void setRunOnce(boolean runOnce) {
        this.runOnce = runOnce;
    }
    
    public boolean isKeepJobOnFailure() {
        return keepJobOnFailure;
    }
    
    @DataBoundSetter
    public void setKeepJobOnFailure(boolean keepJobOnFailure) {
        this.keepJobOnFailure = keepJobOnFailure;
    }
    
    // ====================
    // Slurm Job Submission Fields (maps to v0.0.42_job_desc_msg)
    // ====================
    
    public String getPartition() {
        return partition;
    }
    
    @DataBoundSetter
    public void setPartition(String partition) {
        this.partition = partition != null ? partition : "";
    }
    
    public String getCurrentWorkingDirectory() {
        return currentWorkingDirectory;
    }
    
    @DataBoundSetter
    public void setCurrentWorkingDirectory(String currentWorkingDirectory) {
        this.currentWorkingDirectory = currentWorkingDirectory != null ? currentWorkingDirectory : "/tmp/jenkins";
    }
    
    public Integer getCpusPerTask() {
        return cpusPerTask;
    }
    
    @DataBoundSetter
    public void setCpusPerTask(Integer cpusPerTask) {
        this.cpusPerTask = cpusPerTask != null && cpusPerTask > 0 ? cpusPerTask : 1;
    }
    
    public Long getMemoryPerNode() {
        return memoryPerNode;
    }
    
    @DataBoundSetter
    public void setMemoryPerNode(Long memoryPerNode) {
        this.memoryPerNode = memoryPerNode != null && memoryPerNode > 0 ? memoryPerNode : 1024L;
    }
    
    public String getScript() {
        return script;
    }
    
    @DataBoundSetter
    public void setScript(String script) {
        this.script = script != null ? script : "";
    }
    
    public Integer getTimeLimit() {
        return timeLimit;
    }
    
    @DataBoundSetter
    public void setTimeLimit(Integer timeLimit) {
        this.timeLimit = timeLimit != null && timeLimit > 0 ? timeLimit : 60;
    }
    
    // TRES fields for GPUs and other resources
    public String getTresPerJob() {
        return tresPerJob;
    }
    
    @DataBoundSetter
    public void setTresPerJob(String tresPerJob) {
        this.tresPerJob = tresPerJob != null ? tresPerJob : "";
    }
    
    public String getTresPerNode() {
        return tresPerNode;
    }
    
    @DataBoundSetter
    public void setTresPerNode(String tresPerNode) {
        this.tresPerNode = tresPerNode != null ? tresPerNode : "";
    }
    
    public String getTresPerTask() {
        return tresPerTask;
    }
    
    @DataBoundSetter
    public void setTresPerTask(String tresPerTask) {
        this.tresPerTask = tresPerTask != null ? tresPerTask : "";
    }
    
    // Additional optional fields
    public Integer getMinimumNodes() {
        return minimumNodes;
    }
    
    @DataBoundSetter
    public void setMinimumNodes(Integer minimumNodes) {
        this.minimumNodes = minimumNodes != null && minimumNodes > 0 ? minimumNodes : 1;
    }
    
    public Integer getTasks() {
        return tasks;
    }
    
    @DataBoundSetter
    public void setTasks(Integer tasks) {
        this.tasks = tasks != null && tasks > 0 ? tasks : 1;
    }
    
    public String getAccount() {
        return account;
    }
    
    @DataBoundSetter
    public void setAccount(String account) {
        this.account = account != null ? account : "";
    }
    
    public String getQos() {
        return qos;
    }
    
    @DataBoundSetter
    public void setQos(String qos) {
        this.qos = qos != null ? qos : "";
    }
    
    public String getConstraints() {
        return constraints;
    }
    
    @DataBoundSetter
    public void setConstraints(String constraints) {
        this.constraints = constraints != null ? constraints : "";
    }
    
    public String getRequiredNodes() {
        return requiredNodes;
    }
    
    @DataBoundSetter
    public void setRequiredNodes(String requiredNodes) {
        this.requiredNodes = requiredNodes != null ? requiredNodes : "";
    }

    /**
     * Accept REST API array or comma-separated string for required_nodes.
     */
    @JsonSetter("required_nodes")
    public void setRequiredNodesFromJson(JsonNode node) {
        this.requiredNodes = jsonNodeToCsv(node);
    }
    
    public String getExcludedNodes() {
        return excludedNodes;
    }
    
    @DataBoundSetter
    public void setExcludedNodes(String excludedNodes) {
        this.excludedNodes = excludedNodes != null ? excludedNodes : "";
    }

    /**
     * Accept REST API array or comma-separated string for excluded_nodes.
     */
    @JsonSetter("excluded_nodes")
    public void setExcludedNodesFromJson(JsonNode node) {
        this.excludedNodes = jsonNodeToCsv(node);
    }
    
    public String getEnvironment() {
        return environment;
    }
    
    @DataBoundSetter
    public void setEnvironment(String environment) {
        this.environment = environment != null ? environment : "";
    }

    /**
     * Accept REST API string array or JSON array string for environment.
     */
    @JsonSetter("environment")
    public void setEnvironmentFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            this.environment = "";
        } else if (node.isArray()) {
            this.environment = node.toString();
        } else {
            this.environment = node.asText("");
        }
    }
    
    // ====================
    // Phase 1: Essential fields for pipeline support
    // ====================
    
    // Job identification and features
    @CheckForNull
    public String getJobName() {
        return jobName;
    }
    
    @DataBoundSetter
    public void setJobName(String jobName) {
        this.jobName = jobName != null ? jobName : "";
    }
    
    @CheckForNull
    public String getComment() {
        return comment;
    }
    
    @DataBoundSetter
    public void setComment(String comment) {
        this.comment = comment != null ? comment : "";
    }
    
    @CheckForNull
    public String getPrefer() {
        return prefer;
    }
    
    @DataBoundSetter
    public void setPrefer(String prefer) {
        this.prefer = prefer != null ? prefer : "";
    }
    
    @CheckForNull
    public String getReservation() {
        return reservation;
    }
    
    @DataBoundSetter
    public void setReservation(String reservation) {
        this.reservation = reservation != null ? reservation : "";
    }
    
    @CheckForNull
    public String getNetwork() {
        return network;
    }
    
    @DataBoundSetter
    public void setNetwork(String network) {
        this.network = network != null ? network : "";
    }
    
    @CheckForNull
    public Integer getNice() {
        return nice;
    }
    
    @DataBoundSetter
    public void setNice(Integer nice) {
        this.nice = nice;
    }
    
    @CheckForNull
    public Boolean getReboot() {
        return reboot;
    }
    
    @DataBoundSetter
    public void setReboot(Boolean reboot) {
        this.reboot = reboot;
    }
    
    // Extended TRES fields
    @CheckForNull
    public String getTresPerSocket() {
        return tresPerSocket;
    }
    
    @DataBoundSetter
    public void setTresPerSocket(String tresPerSocket) {
        this.tresPerSocket = tresPerSocket != null ? tresPerSocket : "";
    }
    
    @CheckForNull
    public String getTresBind() {
        return tresBind;
    }
    
    @DataBoundSetter
    public void setTresBind(String tresBind) {
        this.tresBind = tresBind != null ? tresBind : "";
    }
    
    @CheckForNull
    public String getTresFreq() {
        return tresFreq;
    }
    
    @DataBoundSetter
    public void setTresFreq(String tresFreq) {
        this.tresFreq = tresFreq != null ? tresFreq : "";
    }
    
    // Time limits
    @CheckForNull
    public Integer getTimeMinimum() {
        return timeMinimum;
    }
    
    @DataBoundSetter
    public void setTimeMinimum(Integer timeMinimum) {
        this.timeMinimum = timeMinimum;
    }
    
    // Resource allocation - node specs
    @CheckForNull
    public String getNodes() {
        return nodes;
    }
    
    @DataBoundSetter
    public void setNodes(String nodes) {
        this.nodes = nodes != null ? nodes : "";
    }
    
    @CheckForNull
    public Integer getMaximumNodes() {
        return maximumNodes;
    }
    
    @DataBoundSetter
    public void setMaximumNodes(Integer maximumNodes) {
        this.maximumNodes = maximumNodes;
    }
    
    @CheckForNull
    public Integer getMinimumCpus() {
        return minimumCpus;
    }
    
    @DataBoundSetter
    public void setMinimumCpus(Integer minimumCpus) {
        this.minimumCpus = minimumCpus;
    }
    
    @CheckForNull
    public Integer getMaximumCpus() {
        return maximumCpus;
    }
    
    @DataBoundSetter
    public void setMaximumCpus(Integer maximumCpus) {
        this.maximumCpus = maximumCpus;
    }
    
    // Resource allocation - per-node specs
    @CheckForNull
    public Integer getSocketsPerNode() {
        return socketsPerNode;
    }
    
    @DataBoundSetter
    public void setSocketsPerNode(Integer socketsPerNode) {
        this.socketsPerNode = socketsPerNode;
    }
    
    @CheckForNull
    public Integer getThreadsPerCore() {
        return threadsPerCore;
    }
    
    @DataBoundSetter
    public void setThreadsPerCore(Integer threadsPerCore) {
        this.threadsPerCore = threadsPerCore;
    }
    
    @CheckForNull
    public Integer getTasksPerNode() {
        return tasksPerNode;
    }
    
    @DataBoundSetter
    public void setTasksPerNode(Integer tasksPerNode) {
        this.tasksPerNode = tasksPerNode;
    }
    
    @CheckForNull
    public Integer getTasksPerSocket() {
        return tasksPerSocket;
    }
    
    @DataBoundSetter
    public void setTasksPerSocket(Integer tasksPerSocket) {
        this.tasksPerSocket = tasksPerSocket;
    }
    
    @CheckForNull
    public Integer getTasksPerCore() {
        return tasksPerCore;
    }
    
    @DataBoundSetter
    public void setTasksPerCore(Integer tasksPerCore) {
        this.tasksPerCore = tasksPerCore;
    }
    
    @CheckForNull
    public Integer getTasksPerBoard() {
        return tasksPerBoard;
    }
    
    @DataBoundSetter
    public void setTasksPerBoard(Integer tasksPerBoard) {
        this.tasksPerBoard = tasksPerBoard;
    }
    
    @CheckForNull
    public Integer getNtasksPerTres() {
        return ntasksPerTres;
    }
    
    @DataBoundSetter
    public void setNtasksPerTres(Integer ntasksPerTres) {
        this.ntasksPerTres = ntasksPerTres;
    }
    
    @CheckForNull
    public Integer getMinimumCpusPerNode() {
        return minimumCpusPerNode;
    }
    
    @DataBoundSetter
    public void setMinimumCpusPerNode(Integer minimumCpusPerNode) {
        this.minimumCpusPerNode = minimumCpusPerNode;
    }
    
    @CheckForNull
    public Integer getMinimumBoardsPerNode() {
        return minimumBoardsPerNode;
    }
    
    @DataBoundSetter
    public void setMinimumBoardsPerNode(Integer minimumBoardsPerNode) {
        this.minimumBoardsPerNode = minimumBoardsPerNode;
    }
    
    @CheckForNull
    public Integer getMinimumSocketsPerBoard() {
        return minimumSocketsPerBoard;
    }
    
    @DataBoundSetter
    public void setMinimumSocketsPerBoard(Integer minimumSocketsPerBoard) {
        this.minimumSocketsPerBoard = minimumSocketsPerBoard;
    }
    
    // Memory allocation
    @CheckForNull
    public Long getMemoryPerCpu() {
        return memoryPerCpu;
    }
    
    @DataBoundSetter
    public void setMemoryPerCpu(Long memoryPerCpu) {
        this.memoryPerCpu = memoryPerCpu;
    }
    
    @CheckForNull
    public Integer getTemporaryDiskPerNode() {
        return temporaryDiskPerNode;
    }
    
    @DataBoundSetter
    public void setTemporaryDiskPerNode(Integer temporaryDiskPerNode) {
        this.temporaryDiskPerNode = temporaryDiskPerNode;
    }
    
    // I/O redirection
    @CheckForNull
    public String getStandardOutput() {
        return standardOutput;
    }
    
    @DataBoundSetter
    public void setStandardOutput(String standardOutput) {
        this.standardOutput = standardOutput != null ? standardOutput : "";
    }
    
    @CheckForNull
    public String getStandardError() {
        return standardError;
    }
    
    @DataBoundSetter
    public void setStandardError(String standardError) {
        this.standardError = standardError != null ? standardError : "";
    }
    
    @CheckForNull
    public String getStandardInput() {
        return standardInput;
    }
    
    @DataBoundSetter
    public void setStandardInput(String standardInput) {
        this.standardInput = standardInput != null ? standardInput : "";
    }
    
    // Container support (Pyxis/Enroot)
    @CheckForNull
    public PyxisConfig getPyxis() {
        return pyxis;
    }
    
    @DataBoundSetter
    public void setPyxis(PyxisConfig pyxis) {
        this.pyxis = pyxis;
    }

    @CheckForNull
    public AgentLaunchConfig getAgent() {
        return agent;
    }

    @DataBoundSetter
    public void setAgent(AgentLaunchConfig agent) {
        this.agent = agent;
    }
    
    // Pipeline build context (listener for error reporting)
    @CheckForNull
    public TaskListener getListenerOrNull() {
        return listener;
    }
    
    public void setListener(TaskListener listener) {
        this.listener = listener;
    }
    
    // ====================
    // Utility Methods
    // ====================

    private static String jsonNodeToCsv(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(item.asText());
            }
            return sb.toString();
        }
        return node.asText("");
    }
    
    /**
     * Checks if this template can handle the given label.
     */
    public boolean canTake(@CheckForNull String requestedLabel) {
        if (requestedLabel == null || requestedLabel.trim().isEmpty()) {
            return this.label == null || this.label.trim().isEmpty();
        }
        
        if (this.label == null || this.label.trim().isEmpty()) {
            return false;
        }
        
        // Split labels by space and check if any match
        String[] requestedLabels = requestedLabel.trim().split("\\s+");
        String[] templateLabels = this.label.trim().split("\\s+");
        
        for (String reqLabel : requestedLabels) {
            for (String tmpLabel : templateLabels) {
                if (reqLabel.equals(tmpLabel)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Gets instance capacity as integer (for compatibility).
     */
    public int getInstanceCapStr() {
        return instanceCap;
    }
    
    @Override
    public String toString() {
        return String.format("SlurmJobTemplate[name=%s, label=%s, partition=%s, cpus=%d, memory=%dMB, tres=%s]",
                           name, label, partition, cpusPerTask, memoryPerNode, tresPerJob);
    }
    
    // Navigation methods for web UI
    
    /**
     * Checks if the current user has manage permission.
     */
    public boolean hasManagePermission() {
        return jenkins.model.Jenkins.get().hasPermission(jenkins.model.Jenkins.MANAGE);
    }
    
    /**
     * Gets the manage permission object for use in Jelly templates.
     */
    public hudson.security.Permission getManagePermission() {
        return jenkins.model.Jenkins.MANAGE;
    }
    
    /**
     * Handles configuration form submission for individual template editing.
     * Uses @AncestorInPath to get parent cloud from URL hierarchy.
     * Uses @POST for CSRF protection.
     */
    @RequirePOST
    public HttpResponse doConfigSubmit(org.kohsuke.stapler.StaplerRequest req, 
                                        org.kohsuke.stapler.StaplerResponse rsp,
                                        @AncestorInPath SlurmCloud owner) throws Exception {
        if (owner == null) {
            throw new IllegalStateException("Cloud could not be found");
        }
        
        owner.checkManagePermission();
        
        // Capture old name before updating
        String oldName = this.name;
        
        net.sf.json.JSONObject formData = req.getSubmittedForm();
        
        // Update this template with form data
        req.bindJSON(this, formData);
        
        // Save Jenkins configuration
        Jenkins.get().save();
        
        // If name changed, redirect to new name, otherwise stay on current page
        if (oldName != null && !oldName.equals(this.name)) {
            LOGGER.info("Template renamed from '" + oldName + "' to '" + this.name + "', redirecting to new URL");
            return new HttpRedirect("../" + this.name);
        }
        
        // Redirect to template view (current page)
        return new HttpRedirect(".");
    }
    
    /**
     * Deletes the template.
     * Uses @AncestorInPath to get parent cloud from URL hierarchy.
     * Uses @POST for CSRF protection.
     */
    @RequirePOST
    public HttpResponse doDoDelete(@AncestorInPath SlurmCloud owner) throws Exception {
        if (owner == null) {
            throw new IllegalStateException("Cloud could not be found");
        }
        
        owner.checkManagePermission();
        owner.removeTemplate(this);
        Jenkins.get().save();
        
        // Redirect back to templates list
        return new HttpRedirect("../../templates");
    }
    
    @Extension
    @Symbol("slurmJobTemplate")
    public static class DescriptorImpl extends Descriptor<SlurmJobTemplate> {
        
        @Override
        public String getDisplayName() {
            return "Slurm Job Template";
        }
        
        public FormValidation doCheckName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Job template name is required");
            }
            return FormValidation.ok();
        }
        
        public FormValidation doCheckCpusPerTask(@QueryParameter String value) {
            try {
                int cpus = Integer.parseInt(value);
                if (cpus <= 0) {
                    return FormValidation.error("CPUs per task must be greater than 0");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number");
            }
        }
        
        public FormValidation doCheckMemoryPerNode(@QueryParameter String value) {
            try {
                long memory = Long.parseLong(value);
                if (memory <= 0) {
                    return FormValidation.error("Memory per node must be greater than 0 MB");
                }
                if (memory < 512) {
                    return FormValidation.warning("Memory less than 512MB might be insufficient for Jenkins agent");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number - enter memory in MB (e.g., 1024 for 1GB)");
            }
        }
        
        public FormValidation doCheckTimeLimit(@QueryParameter String value) {
            try {
                int minutes = Integer.parseInt(value);
                if (minutes <= 0) {
                    return FormValidation.error("Time limit must be greater than 0 minutes");
                }
                if (minutes < 10) {
                    return FormValidation.warning("Time limit less than 10 minutes might be too short");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number - enter time limit in minutes");
            }
        }
        
        public FormValidation doCheckTresPerJob(@QueryParameter String value) {
            if (value != null && !value.trim().isEmpty()) {
                // Basic validation for TRES format: type:name:count or type:count
                // Examples: gres/gpu:gfx942:1, gres/gpu:2
                if (!value.matches("\\w+(/\\w+)?(:\\w+)?(:\\d+)?")) {
                    return FormValidation.warning("TRES format should be like 'gres/gpu:gfx942:1' or 'gres/gpu:2'");
                }
            }
            return FormValidation.ok();
        }
        
        public FormValidation doCheckIdleMinutes(@QueryParameter String value) {
            try {
                int minutes = Integer.parseInt(value);
                if (minutes < 0) {
                    return FormValidation.error("Idle minutes cannot be negative");
                }
                if (minutes == 0) {
                    return FormValidation.warning(
                        "Using idleMinutes=0 enables one-shot mode: agent will terminate immediately after completing a build. " +
                        "This may cause build assignment failures if there are network delays. " +
                        "Consider using at least 1 minute for more reliable agent provisioning."
                    );
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number - enter idle minutes as an integer");
            }
        }
        
        public FormValidation doCheckInstanceCap(@QueryParameter String value) {
            try {
                int cap = Integer.parseInt(value);
                if (cap <= 0) {
                    return FormValidation.error("Instance capacity must be greater than 0");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number");
            }
        }
        
        public FormValidation doCheckMinimumNodes(@QueryParameter String value) {
            try {
                int nodes = Integer.parseInt(value);
                if (nodes <= 0) {
                    return FormValidation.error("Minimum nodes must be greater than 0");
                }
                if (nodes > 1) {
                    return FormValidation.warning("Jenkins agents typically run on single nodes. Multi-node jobs require special configuration.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number");
            }
        }
        
        public FormValidation doCheckTasks(@QueryParameter String value) {
            try {
                int tasks = Integer.parseInt(value);
                if (tasks <= 0) {
                    return FormValidation.error("Number of tasks must be greater than 0");
                }
                if (tasks > 1) {
                    return FormValidation.warning("Jenkins agents typically run as single tasks. Multiple tasks require special configuration.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number");
            }
        }
    }
}
