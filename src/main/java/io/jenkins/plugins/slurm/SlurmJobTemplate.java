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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Represents a SLURM job template that defines the parameters for submitting
 * jobs to a SLURM cluster. Similar to Kubernetes PodTemplate, this allows
 * users to define different job configurations for different build requirements.
 */
public class SlurmJobTemplate extends AbstractDescribableImpl<SlurmJobTemplate> {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmJobTemplate.class.getName());
    
    private final String id;
    private String name;
    private String label;
    private String partition;
    private int nodes;
    private int ntasks;
    private int cpusPerTask;
    private String memory;
    private String timeLimit;
    private String jobScript;
    private String workingDirectory;
    private Node.Mode nodeUsageMode;
    private int instanceCapStr;
    private int idleMinutes;
    private String additionalSbatchArgs;
    
    @DataBoundConstructor
    public SlurmJobTemplate() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.label = "";
        this.partition = "";
        this.nodes = 1;
        this.ntasks = 1;
        this.cpusPerTask = 1;
        this.memory = "1G";
        this.timeLimit = "1:00:00";
        this.jobScript = "";
        this.workingDirectory = "/tmp";
        this.nodeUsageMode = Node.Mode.EXCLUSIVE;
        this.instanceCapStr = 1;
        this.idleMinutes = 5;
        this.additionalSbatchArgs = "";
    }
    
    public SlurmJobTemplate(String name, String label) {
        this();
        this.name = name;
        this.label = label;
    }
    
    // Core identification fields
    public String getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    @DataBoundSetter
    public void setName(String name) {
        this.name = name;
    }
    
    public String getLabel() {
        return label;
    }
    
    @DataBoundSetter
    public void setLabel(String label) {
        this.label = label;
    }
    
    // SLURM job configuration fields
    public String getPartition() {
        return partition;
    }
    
    @DataBoundSetter
    public void setPartition(String partition) {
        this.partition = partition;
    }
    
    public int getNodes() {
        return nodes;
    }
    
    @DataBoundSetter
    public void setNodes(int nodes) {
        this.nodes = nodes > 0 ? nodes : 1;
    }
    
    public int getNtasks() {
        return ntasks;
    }
    
    @DataBoundSetter
    public void setNtasks(int ntasks) {
        this.ntasks = ntasks > 0 ? ntasks : 1;
    }
    
    public int getCpusPerTask() {
        return cpusPerTask;
    }
    
    @DataBoundSetter
    public void setCpusPerTask(int cpusPerTask) {
        this.cpusPerTask = cpusPerTask > 0 ? cpusPerTask : 1;
    }
    
    public String getMemory() {
        return memory;
    }
    
    @DataBoundSetter
    public void setMemory(String memory) {
        this.memory = memory != null && !memory.trim().isEmpty() ? memory : "1G";
    }
    
    public String getTimeLimit() {
        return timeLimit;
    }
    
    @DataBoundSetter
    public void setTimeLimit(String timeLimit) {
        this.timeLimit = timeLimit != null && !timeLimit.trim().isEmpty() ? timeLimit : "1:00:00";
    }
    
    public String getJobScript() {
        return jobScript;
    }
    
    @DataBoundSetter
    public void setJobScript(String jobScript) {
        this.jobScript = jobScript != null ? jobScript : "";
    }
    
    public String getWorkingDirectory() {
        return workingDirectory;
    }
    
    @DataBoundSetter
    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory != null && !workingDirectory.trim().isEmpty() ? workingDirectory : "/tmp";
    }
    
    public Node.Mode getNodeUsageMode() {
        return nodeUsageMode;
    }
    
    @DataBoundSetter
    public void setNodeUsageMode(Node.Mode nodeUsageMode) {
        this.nodeUsageMode = nodeUsageMode != null ? nodeUsageMode : Node.Mode.EXCLUSIVE;
    }
    
    public int getInstanceCapStr() {
        return instanceCapStr;
    }
    
    @DataBoundSetter
    public void setInstanceCapStr(int instanceCapStr) {
        this.instanceCapStr = instanceCapStr > 0 ? instanceCapStr : 1;
    }
    
    public int getIdleMinutes() {
        return idleMinutes;
    }
    
    @DataBoundSetter
    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes > 0 ? idleMinutes : 5;
    }
    
    public String getAdditionalSbatchArgs() {
        return additionalSbatchArgs;
    }
    
    @DataBoundSetter
    public void setAdditionalSbatchArgs(String additionalSbatchArgs) {
        this.additionalSbatchArgs = additionalSbatchArgs != null ? additionalSbatchArgs : "";
    }
    
    /**
     * Generates the SLURM sbatch script for this job template.
     */
    public String generateSbatchScript(String agentName) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("#SBATCH --job-name=").append(agentName).append("\n");
        
        if (partition != null && !partition.trim().isEmpty()) {
            script.append("#SBATCH --partition=").append(partition).append("\n");
        }
        
        script.append("#SBATCH --nodes=").append(nodes).append("\n");
        script.append("#SBATCH --ntasks=").append(ntasks).append("\n");
        script.append("#SBATCH --cpus-per-task=").append(cpusPerTask).append("\n");
        script.append("#SBATCH --mem=").append(memory).append("\n");
        script.append("#SBATCH --time=").append(timeLimit).append("\n");
        
        if (workingDirectory != null && !workingDirectory.trim().isEmpty()) {
            script.append("#SBATCH --chdir=").append(workingDirectory).append("\n");
        }
        
        // Add any additional sbatch arguments
        if (additionalSbatchArgs != null && !additionalSbatchArgs.trim().isEmpty()) {
            for (String arg : additionalSbatchArgs.split("\\s+")) {
                if (!arg.trim().isEmpty()) {
                    script.append("#SBATCH ").append(arg).append("\n");
                }
            }
        }
        
        script.append("\n");
        
        // Add custom job script if provided
        if (jobScript != null && !jobScript.trim().isEmpty()) {
            script.append(jobScript).append("\n");
        } else {
            // Default: just keep the job running and wait for Jenkins agent
            script.append("# Keep job running for Jenkins agent\n");
            script.append("echo \"SLURM job started for Jenkins agent: ").append(agentName).append("\"\n");
            script.append("echo \"Allocated nodes: $SLURM_JOB_NODELIST\"\n");
            script.append("echo \"Job ID: $SLURM_JOB_ID\"\n");
            script.append("\n");
            script.append("# Wait for Jenkins agent to connect and complete\n");
            script.append("# The Jenkins agent process will take over from here\n");
            script.append("while true; do\n");
            script.append("    sleep 30\n");
            script.append("    # Check if Jenkins agent is still running\n");
            script.append("    if ! pgrep -f 'jenkins.*agent' > /dev/null; then\n");
            script.append("        echo \"Jenkins agent process not found, exiting\"\n");
            script.append("        break\n");
            script.append("    fi\n");
            script.append("done\n");
        }
        
        return script.toString();
    }
    
    /**
     * Checks if this job template can be used for the given label.
     */
    public boolean canTake(@CheckForNull String requestedLabel) {
        if (requestedLabel == null || requestedLabel.trim().isEmpty()) {
            return true; // Can take any job if no specific label requested
        }
        
        if (label == null || label.trim().isEmpty()) {
            return true; // This template has no label restriction
        }
        
        // Simple label matching - can be enhanced for complex label expressions
        return label.equals(requestedLabel);
    }
    
    @Override
    public String toString() {
        return "SlurmJobTemplate{" +
                "name='" + name + '\'' +
                ", label='" + label + '\'' +
                ", partition='" + partition + '\'' +
                ", nodes=" + nodes +
                ", cpus=" + cpusPerTask +
                ", memory='" + memory + '\'' +
                '}';
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
     */
    public void doConfigSubmit(org.kohsuke.stapler.StaplerRequest req, org.kohsuke.stapler.StaplerResponse rsp) 
            throws Exception {
        jenkins.model.Jenkins.get().checkPermission(jenkins.model.Jenkins.MANAGE);
        
        net.sf.json.JSONObject formData = req.getSubmittedForm();
        
        // Update this template with form data
        req.bindJSON(this, formData);
        
        // Configuration is handled by Jenkins automatically
        // No explicit save needed as changes are persisted through the form submission
        
        rsp.sendRedirect(".");
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
        
        public FormValidation doCheckNodes(@QueryParameter String value) {
            try {
                int nodes = Integer.parseInt(value);
                if (nodes <= 0) {
                    return FormValidation.error("Number of nodes must be greater than 0");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number");
            }
        }
        
        public FormValidation doCheckNtasks(@QueryParameter String value) {
            try {
                int ntasks = Integer.parseInt(value);
                if (ntasks <= 0) {
                    return FormValidation.error("Number of tasks must be greater than 0");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number");
            }
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
        
        public FormValidation doCheckMemory(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Memory specification is required");
            }
            // Basic validation for memory format (e.g., 1G, 512M, 2048)
            if (!value.matches("\\d+[KMGT]?")) {
                return FormValidation.warning("Memory format should be like '1G', '512M', or '2048'");
            }
            return FormValidation.ok();
        }
        
        public FormValidation doCheckTimeLimit(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Time limit is required");
            }
            // Basic validation for time format (e.g., 1:00:00, 30:00, 60)
            if (!value.matches("\\d{1,2}(:\\d{2}){0,2}")) {
                return FormValidation.warning("Time format should be like '1:00:00', '30:00', or '60'");
            }
            return FormValidation.ok();
        }
        
        public FormValidation doCheckInstanceCapStr(@QueryParameter String value) {
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
    }
}