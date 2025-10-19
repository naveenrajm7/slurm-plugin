package io.jenkins.plugins.slurm;

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
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Represents a SLURM job template that defines the parameters for submitting
 * jobs to a SLURM cluster. This template maps directly to SLURM's job_desc_msg structure
 * from the REST API, allowing users to define different job configurations for different
 * build requirements.
 * 
 * The template structure follows SLURM's v0.0.42_job_desc_msg to minimize processing
 * and allow future code-based template definitions to match the API structure.
 */
public class SlurmJobTemplate extends AbstractDescribableImpl<SlurmJobTemplate> {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmJobTemplate.class.getName());
    
    // Template metadata (not part of SLURM job submission)
    private final String id;
    private String name;
    private String label;
    private Node.Mode nodeUsageMode;
    private int instanceCap;
    private int idleMinutes;
    private boolean runOnce;  // If true, agent terminates after one build (default: true)
    private boolean keepJobOnFailure;  // If true, don't cancel SLURM job when build fails (for debugging)
    
    // Core SLURM job submission fields (maps to v0.0.42_job_desc_msg)
    private String partition;                    // partition: which SLURM partition to use
    private String currentWorkingDirectory;      // current_working_directory: where to run the job
    private Integer cpusPerTask;                 // cpus_per_task: CPUs per task
    private Long memoryPerNode;                  // memory_per_node: memory in MB per node
    private String script;                       // script: batch script content (will contain Jenkins agent launcher)
    private Integer timeLimit;                   // time_limit: max runtime in minutes
    
    // TRES (Trackable RESources) - for GPUs and other resources
    private String tresPerJob;                   // tres_per_job: e.g., "gres/gpu:gfx942:1"
    private String tresPerNode;                  // tres_per_node: TRES per node
    private String tresPerTask;                  // tres_per_task: TRES per task
    
    // Additional optional fields
    private Integer minimumNodes;                // minimum_nodes: minimum node count (default 1)
    private Integer tasks;                       // tasks: number of tasks (default 1)
    private String account;                      // account: SLURM account to charge
    private String qos;                          // qos: Quality of Service
    private String constraints;                  // constraints: required features
    private String environment;                  // environment: environment variables (as JSON array string)
    
    @DataBoundConstructor
    public SlurmJobTemplate() {
        this.id = UUID.randomUUID().toString();
        this.name = "default";
        this.label = "";
        this.nodeUsageMode = Node.Mode.EXCLUSIVE;
        this.instanceCap = 1;
        this.idleMinutes = 5;
        this.runOnce = true;  // Default: terminate after one build
        this.keepJobOnFailure = false;  // Default: always cancel job on termination
        
        // SLURM defaults (keeping 1 node, 1 task for Jenkins agent)
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
    
    public String getName() {
        return name;
    }
    
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
        this.idleMinutes = idleMinutes >= 0 ? idleMinutes : 5;
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
    // SLURM Job Submission Fields (maps to v0.0.42_job_desc_msg)
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
    
    public String getEnvironment() {
        return environment;
    }
    
    @DataBoundSetter
    public void setEnvironment(String environment) {
        this.environment = environment != null ? environment : "";
    }
    
    // ====================
    // Utility Methods
    // ====================
    
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
            return "SLURM Job Template";
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