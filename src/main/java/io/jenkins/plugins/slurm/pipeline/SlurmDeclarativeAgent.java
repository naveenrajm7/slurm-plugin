package io.jenkins.plugins.slurm.pipeline;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Label;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.slurm.PyxisConfig;
import io.jenkins.plugins.slurm.SlurmCloud;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentDescriptor;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.RetryableDeclarativeAgent;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Declarative Pipeline agent for SLURM.
 * 
 * Supports three modes:
 * 1. Property-based configuration (similar to Kubernetes plugin)
 * 2. Inline JSON configuration for complete SLURM REST API flexibility
 * 3. JSON file from workspace (requires SCM checkout)
 * 
 * Example 1: Property-based
 * <pre>
 * agent {
 *   slurm {
 *     cloud 'my-slurm-cluster'
 *     partition 'gpu'
 *     gres 'gpu:gfx1030:1'
 *     cpus 16
 *     memory '32G'
 *     time '02:00:00'
 *     workingDir '/scratch/jenkins'
 *   }
 * }
 * </pre>
 * 
 * Example 2: Inline JSON
 * <pre>
 * agent {
 *   slurm {
 *     cloud 'my-slurm-cluster'
 *     json '''
 *       {
 *         "partition": "gpu",
 *         "gres": "gpu:gfx1030:1",
 *         "cpus": 16,
 *         "memory": "32G",
 *         "time": "02:00:00",
 *         "containerImage": "/path/to/image.sqsh"
 *       }
 *     '''
 *   }
 * }
 * </pre>
 * 
 * Example 3: JSON from file (requires SCM)
 * <pre>
 * agent {
 *   slurm {
 *     cloud 'my-slurm-cluster'
 *     jsonFile '.jenkins/slurm-gpu-config.json'
 *   }
 * }
 * </pre>
 */
public class SlurmDeclarativeAgent extends RetryableDeclarativeAgent<SlurmDeclarativeAgent> {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmDeclarativeAgent.class.getName());
    
    // Cloud and label configuration
    @CheckForNull
    private String cloud;
    
    @CheckForNull
    private String label;
    
    @CheckForNull
    private String customWorkspace;
    
    @CheckForNull
    private String inheritFrom;  // Inherit from existing template
    
    // JSON configuration (alternative to property-based)
    @CheckForNull
    private String json;
    
    @CheckForNull
    private String jsonFile;  // Path to JSON file in SCM
    
    // Core SLURM job submission fields
    @CheckForNull
    private String partition;
    
    @CheckForNull
    private String workingDir;
    
    @CheckForNull
    private Integer cpus;  // Maps to cpus_per_task
    
    @CheckForNull
    private String memory;  // Memory (e.g., "32G", "4096M") - maps to memory_per_node
    
    @CheckForNull
    private String time;  // Time limit (e.g., "02:00:00", "120") - maps to time_limit
    
    @CheckForNull
    private String gres;  // Generic resources (e.g., "gpu:gfx1030:1") - maps to tres_per_job
    
    @CheckForNull
    private String account;  // SLURM account
    
    @CheckForNull
    private String qos;  // Quality of Service
    
    @CheckForNull
    private String reservation;  // Reservation name
    
    @CheckForNull
    private String constraints;  // Required features
    
    @CheckForNull
    private String prefer;  // Preferred features
    
    // Resource allocation
    @CheckForNull
    private String nodes;  // Node count (e.g., "1", "1-4:2")
    
    @CheckForNull
    private Integer tasks;  // Number of tasks
    
    @CheckForNull
    private Integer tasksPerNode;  // Tasks per node
    
    @CheckForNull
    private Integer ntasksPerTres;  // Tasks per TRES (e.g., per GPU)
    
    // Container support (Pyxis/Enroot)
    @CheckForNull
    private String containerImage;  // Container image path or URI
    
    @CheckForNull
    private String containerMounts;  // Mount specifications
    
    @CheckForNull
    private Boolean containerMountHome;  // Mount home directory
    
    @CheckForNull
    private String containerWorkdir;  // Working directory in container
    
    // I/O redirection
    @CheckForNull
    private String standardOutput;  // Path to stdout
    
    @CheckForNull
    private String standardError;  // Path to stderr
    
    // Agent configuration
    private int idleMinutes = 5;  // Idle timeout in minutes
    
    private boolean runOnce = true;  // Terminate after one build
    
    private boolean keepJobOnFailure = false;  // Keep SLURM job on failure for debugging
    
    @DataBoundConstructor
    public SlurmDeclarativeAgent() {}
    
    // ====================
    // Cloud and Label
    // ====================
    
    @CheckForNull
    public String getCloud() {
        return cloud;
    }
    
    @DataBoundSetter
    public void setCloud(String cloud) {
        this.cloud = Util.fixEmpty(cloud);
    }
    
    @CheckForNull
    public String getLabel() {
        return label;
    }
    
    public String getLabelExpression() {
        return label != null
                ? Label.parse(label).stream().map(Objects::toString).sorted().collect(Collectors.joining(" && "))
                : null;
    }
    
    @DataBoundSetter
    public void setLabel(String label) {
        this.label = Util.fixEmpty(label);
    }
    
    @CheckForNull
    public String getCustomWorkspace() {
        return customWorkspace;
    }
    
    @DataBoundSetter
    public void setCustomWorkspace(String customWorkspace) {
        this.customWorkspace = customWorkspace;
    }
    
    @CheckForNull
    public String getInheritFrom() {
        return inheritFrom;
    }
    
    @DataBoundSetter
    public void setInheritFrom(String inheritFrom) {
        this.inheritFrom = Util.fixEmpty(inheritFrom);
    }
    
    // ====================
    // JSON Configuration
    // ====================
    
    @CheckForNull
    public String getJson() {
        return json;
    }
    
    @DataBoundSetter
    public void setJson(String json) {
        this.json = Util.fixEmpty(json);
    }
    
    @CheckForNull
    public String getJsonFile() {
        return jsonFile;
    }
    
    @DataBoundSetter
    public void setJsonFile(String jsonFile) {
        this.jsonFile = Util.fixEmpty(jsonFile);
    }
    
    /**
     * Checks if this agent has SCM context (i.e., running in a job with SCM checkout).
     * Used to determine if we can read JSON files from the workspace.
     */
    public boolean hasScmContext(Object script) {
        try {
            if (script instanceof org.jenkinsci.plugins.workflow.cps.CpsScript) {
                org.jenkinsci.plugins.workflow.cps.CpsScript cpsScript = 
                    (org.jenkinsci.plugins.workflow.cps.CpsScript) script;
                return cpsScript.getBinding().hasVariable("scm");
            }
        } catch (Exception e) {
            LOGGER.fine("Could not determine SCM context: " + e.getMessage());
        }
        return false;
    }
    
    // ====================
    // Core SLURM Fields
    // ====================
    
    @CheckForNull
    public String getPartition() {
        return partition;
    }
    
    @DataBoundSetter
    public void setPartition(String partition) {
        this.partition = Util.fixEmpty(partition);
    }
    
    @CheckForNull
    public String getWorkingDir() {
        return workingDir;
    }
    
    @DataBoundSetter
    public void setWorkingDir(String workingDir) {
        this.workingDir = Util.fixEmpty(workingDir);
    }
    
    @CheckForNull
    public Integer getCpus() {
        return cpus;
    }
    
    @DataBoundSetter
    public void setCpus(Integer cpus) {
        this.cpus = cpus;
    }
    
    @CheckForNull
    public String getMemory() {
        return memory;
    }
    
    @DataBoundSetter
    public void setMemory(String memory) {
        this.memory = Util.fixEmpty(memory);
    }
    
    @CheckForNull
    public String getTime() {
        return time;
    }
    
    @DataBoundSetter
    public void setTime(String time) {
        this.time = Util.fixEmpty(time);
    }
    
    @CheckForNull
    public String getGres() {
        return gres;
    }
    
    @DataBoundSetter
    public void setGres(String gres) {
        this.gres = Util.fixEmpty(gres);
    }
    
    @CheckForNull
    public String getAccount() {
        return account;
    }
    
    @DataBoundSetter
    public void setAccount(String account) {
        this.account = Util.fixEmpty(account);
    }
    
    @CheckForNull
    public String getQos() {
        return qos;
    }
    
    @DataBoundSetter
    public void setQos(String qos) {
        this.qos = Util.fixEmpty(qos);
    }
    
    @CheckForNull
    public String getReservation() {
        return reservation;
    }
    
    @DataBoundSetter
    public void setReservation(String reservation) {
        this.reservation = Util.fixEmpty(reservation);
    }
    
    @CheckForNull
    public String getConstraints() {
        return constraints;
    }
    
    @DataBoundSetter
    public void setConstraints(String constraints) {
        this.constraints = Util.fixEmpty(constraints);
    }
    
    @CheckForNull
    public String getPrefer() {
        return prefer;
    }
    
    @DataBoundSetter
    public void setPrefer(String prefer) {
        this.prefer = Util.fixEmpty(prefer);
    }
    
    // ====================
    // Resource Allocation
    // ====================
    
    @CheckForNull
    public String getNodes() {
        return nodes;
    }
    
    @DataBoundSetter
    public void setNodes(String nodes) {
        this.nodes = Util.fixEmpty(nodes);
    }
    
    @CheckForNull
    public Integer getTasks() {
        return tasks;
    }
    
    @DataBoundSetter
    public void setTasks(Integer tasks) {
        this.tasks = tasks;
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
    public Integer getNtasksPerTres() {
        return ntasksPerTres;
    }
    
    @DataBoundSetter
    public void setNtasksPerTres(Integer ntasksPerTres) {
        this.ntasksPerTres = ntasksPerTres;
    }
    
    // ====================
    // Container Support
    // ====================
    
    @CheckForNull
    public String getContainerImage() {
        return containerImage;
    }
    
    @DataBoundSetter
    public void setContainerImage(String containerImage) {
        this.containerImage = Util.fixEmpty(containerImage);
    }
    
    @CheckForNull
    public String getContainerMounts() {
        return containerMounts;
    }
    
    @DataBoundSetter
    public void setContainerMounts(String containerMounts) {
        this.containerMounts = Util.fixEmpty(containerMounts);
    }
    
    @CheckForNull
    public Boolean getContainerMountHome() {
        return containerMountHome;
    }
    
    @DataBoundSetter
    public void setContainerMountHome(Boolean containerMountHome) {
        this.containerMountHome = containerMountHome;
    }
    
    @CheckForNull
    public String getContainerWorkdir() {
        return containerWorkdir;
    }
    
    @DataBoundSetter
    public void setContainerWorkdir(String containerWorkdir) {
        this.containerWorkdir = Util.fixEmpty(containerWorkdir);
    }
    
    // ====================
    // I/O Redirection
    // ====================
    
    @CheckForNull
    public String getStandardOutput() {
        return standardOutput;
    }
    
    @DataBoundSetter
    public void setStandardOutput(String standardOutput) {
        this.standardOutput = Util.fixEmpty(standardOutput);
    }
    
    @CheckForNull
    public String getStandardError() {
        return standardError;
    }
    
    @DataBoundSetter
    public void setStandardError(String standardError) {
        this.standardError = Util.fixEmpty(standardError);
    }
    
    // ====================
    // Agent Configuration
    // ====================
    
    public int getIdleMinutes() {
        return idleMinutes;
    }
    
    @DataBoundSetter
    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes;
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
    // Conversion to Arguments Map
    // ====================
    
    /**
     * Convert this declarative agent configuration to arguments map
     * for the SlurmJobTemplateStep.
     */
    public Map<String, Object> getAsArgs() {
        Map<String, Object> argMap = new TreeMap<>();
        
        if (!StringUtils.isEmpty(cloud)) {
            argMap.put("cloud", cloud);
        }
        if (label != null) {
            argMap.put("label", label);
        }
        if (!StringUtils.isEmpty(inheritFrom)) {
            argMap.put("inheritFrom", inheritFrom);
        }
        
        // JSON configuration takes precedence
        if (!StringUtils.isEmpty(json)) {
            argMap.put("json", json);
            return argMap;  // JSON mode - return early
        }
        
        // Property-based configuration
        if (!StringUtils.isEmpty(partition)) {
            argMap.put("partition", partition);
        }
        if (!StringUtils.isEmpty(workingDir)) {
            argMap.put("workingDir", workingDir);
        }
        if (cpus != null) {
            argMap.put("cpus", cpus);
        }
        if (!StringUtils.isEmpty(memory)) {
            argMap.put("memory", memory);
        }
        if (!StringUtils.isEmpty(time)) {
            argMap.put("time", time);
        }
        if (!StringUtils.isEmpty(gres)) {
            argMap.put("gres", gres);
        }
        if (!StringUtils.isEmpty(account)) {
            argMap.put("account", account);
        }
        if (!StringUtils.isEmpty(qos)) {
            argMap.put("qos", qos);
        }
        if (!StringUtils.isEmpty(reservation)) {
            argMap.put("reservation", reservation);
        }
        if (!StringUtils.isEmpty(constraints)) {
            argMap.put("constraints", constraints);
        }
        if (!StringUtils.isEmpty(prefer)) {
            argMap.put("prefer", prefer);
        }
        if (!StringUtils.isEmpty(nodes)) {
            argMap.put("nodes", nodes);
        }
        if (tasks != null) {
            argMap.put("tasks", tasks);
        }
        if (tasksPerNode != null) {
            argMap.put("tasksPerNode", tasksPerNode);
        }
        if (ntasksPerTres != null) {
            argMap.put("ntasksPerTres", ntasksPerTres);
        }
        
        // Container configuration
        if (!StringUtils.isEmpty(containerImage)) {
            PyxisConfig pyxis = new PyxisConfig();
            pyxis.setContainerImage(containerImage);
            if (!StringUtils.isEmpty(containerMounts)) {
                pyxis.setContainerMounts(containerMounts);
            }
            if (containerMountHome != null) {
                pyxis.setContainerMountHome(containerMountHome);
            }
            if (!StringUtils.isEmpty(containerWorkdir)) {
                pyxis.setContainerWorkdir(containerWorkdir);
            }
            argMap.put("pyxis", pyxis);
        }
        
        // I/O redirection
        if (!StringUtils.isEmpty(standardOutput)) {
            argMap.put("standardOutput", standardOutput);
        }
        if (!StringUtils.isEmpty(standardError)) {
            argMap.put("standardError", standardError);
        }
        
        // Agent configuration
        if (idleMinutes != 5) {  // Only include if not default
            argMap.put("idleMinutes", idleMinutes);
        }
        argMap.put("runOnce", runOnce);
        argMap.put("keepJobOnFailure", keepJobOnFailure);
        
        return argMap;
    }
    
    @Extension(optional = true)
    @Symbol("slurm")
    public static class DescriptorImpl extends DeclarativeAgentDescriptor<SlurmDeclarativeAgent> {
        
        @NonNull
        @Override
        public String getDisplayName() {
            return "SLURM";
        }
        
        @SuppressWarnings("unused") // used by stapler/jelly
        public ListBoxModel doFillCloudItems() {
            ListBoxModel model = new ListBoxModel();
            model.add("", "");
            Jenkins jenkins = Jenkins.get();
            for (SlurmCloud cloud : jenkins.clouds.getAll(SlurmCloud.class)) {
                model.add(cloud.getDisplayName(), cloud.name);
            }
            return model;
        }
        
        @SuppressWarnings("unused") // used by stapler/jelly
        public ListBoxModel doFillInheritFromItems(@QueryParameter("cloud") String cloudName) {
            ListBoxModel model = new ListBoxModel();
            model.add("", "");
            
            if (!StringUtils.isEmpty(cloudName)) {
                Jenkins jenkins = Jenkins.get();
                for (SlurmCloud cloud : jenkins.clouds.getAll(SlurmCloud.class)) {
                    if (cloudName.equals(cloud.name)) {
                        if (cloud.getTemplates() != null) {
                            cloud.getTemplates().forEach(template -> 
                                model.add(template.getName(), template.getId())
                            );
                        }
                        break;
                    }
                }
            }
            
            return model;
        }
    }
}
