package io.jenkins.plugins.slurm.pipeline;

import com.google.common.collect.ImmutableSet;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Label;
import hudson.model.Node;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.slurm.PyxisConfig;
import io.jenkins.plugins.slurm.SlurmCloud;
import io.jenkins.plugins.slurm.SlurmJobTemplate;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.io.Serializable;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Pipeline step to create a SLURM job template context.
 * 
 * This step creates a temporary SLURM job template from declarative configuration
 * and provides it as context for nested pipeline steps.
 * 
 * Example usage:
 * <pre>
 * slurmJobTemplate(
 *   cloud: 'my-cluster',
 *   partition: 'gpu',
 *   cpus: 16,
 *   memory: '32G',
 *   gres: 'gpu:gfx1030:1'
 * ) {
 *   node(POD_LABEL) {
 *     sh 'nvidia-smi'
 *   }
 * }
 * </pre>
 */
public class SlurmJobTemplateStep extends Step implements Serializable {
    
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(SlurmJobTemplateStep.class.getName());
    
    // Cloud configuration
    @CheckForNull
    private String cloud;
    
    @CheckForNull
    private String label;
    
    @CheckForNull
    private String inheritFrom;  // Template ID to inherit from
    
    // JSON configuration
    @CheckForNull
    private String json;
    
    // Core SLURM fields (mirroring SlurmJobTemplate)
    @CheckForNull
    private String partition;
    
    @CheckForNull
    private String workingDir;
    
    @CheckForNull
    private Integer cpus;
    
    @CheckForNull
    private String memory;
    
    @CheckForNull
    private String time;
    
    @CheckForNull
    private String gres;
    
    @CheckForNull
    private String account;
    
    @CheckForNull
    private String qos;
    
    @CheckForNull
    private String reservation;
    
    @CheckForNull
    private String constraints;
    
    @CheckForNull
    private String prefer;
    
    @CheckForNull
    private String nodes;
    
    @CheckForNull
    private Integer tasks;
    
    @CheckForNull
    private Integer tasksPerNode;
    
    @CheckForNull
    private Integer ntasksPerTres;
    
    // Container support
    @CheckForNull
    private PyxisConfig pyxis;
    
    // I/O
    @CheckForNull
    private String standardOutput;
    
    @CheckForNull
    private String standardError;
    
    // Agent configuration
    private int idleMinutes = 5;
    private boolean runOnce = true;
    private boolean keepJobOnFailure = false;
    
    @DataBoundConstructor
    public SlurmJobTemplateStep() {}
    
    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new SlurmJobTemplateStepExecution(this, context);
    }
    
    /**
     * Create a SlurmJobTemplate from this step's configuration.
     */
    @NonNull
    public SlurmJobTemplate buildJobTemplate(@NonNull SlurmCloud cloud) {
        SlurmJobTemplate template;
        
        // If inheriting from existing template, start with that
        if (!StringUtils.isEmpty(inheritFrom) && cloud.getTemplates() != null) {
            SlurmJobTemplate parentTemplate = cloud.getTemplates().stream()
                    .filter(t -> inheritFrom.equals(t.getId()) || inheritFrom.equals(t.getName()))
                    .findFirst()
                    .orElse(null);
            
            if (parentTemplate != null) {
                LOGGER.fine("Inheriting from template: " + parentTemplate.getName());
                template = parentTemplate;  // TODO: Deep copy for safety
            } else {
                LOGGER.warning("Could not find template to inherit from: " + inheritFrom);
                template = new SlurmJobTemplate();
            }
        } else {
            template = new SlurmJobTemplate();
        }
        
        // Apply JSON configuration first (if provided)
        if (!StringUtils.isEmpty(json)) {
            LOGGER.fine("Applying JSON configuration");
            applyJsonConfiguration(template, json);
        }
        
        // Then apply individual properties (which override JSON if both specified)
        if (!StringUtils.isEmpty(label)) {
            template.setLabel(label);
        }
        
        // Core SLURM fields
        if (!StringUtils.isEmpty(partition)) {
            template.setPartition(partition);
        }
        if (!StringUtils.isEmpty(workingDir)) {
            template.setCurrentWorkingDirectory(workingDir);
        }
        if (cpus != null) {
            template.setCpusPerTask(cpus);
        }
        if (!StringUtils.isEmpty(memory)) {
            // Parse memory string (e.g., "32G", "4096M") to MB
            template.setMemoryPerNode(parseMemoryToMB(memory));
        }
        if (!StringUtils.isEmpty(time)) {
            // Parse time string (e.g., "02:00:00", "120") to minutes
            template.setTimeLimit(parseTimeToMinutes(time));
        }
        if (!StringUtils.isEmpty(gres)) {
            template.setTresPerJob(gres);
        }
        if (!StringUtils.isEmpty(account)) {
            template.setAccount(account);
        }
        if (!StringUtils.isEmpty(qos)) {
            template.setQos(qos);
        }
        if (!StringUtils.isEmpty(reservation)) {
            template.setReservation(reservation);
        }
        if (!StringUtils.isEmpty(constraints)) {
            template.setConstraints(constraints);
        }
        if (!StringUtils.isEmpty(prefer)) {
            template.setPrefer(prefer);
        }
        if (!StringUtils.isEmpty(nodes)) {
            template.setNodes(nodes);
        }
        if (tasks != null) {
            template.setTasks(tasks);
        }
        if (tasksPerNode != null) {
            template.setTasksPerNode(tasksPerNode);
        }
        if (ntasksPerTres != null) {
            template.setNtasksPerTres(ntasksPerTres);
        }
        
        // Container support
        if (pyxis != null && pyxis.isConfigured()) {
            template.setPyxis(pyxis);
        }
        
        // I/O
        if (!StringUtils.isEmpty(standardOutput)) {
            template.setStandardOutput(standardOutput);
        }
        if (!StringUtils.isEmpty(standardError)) {
            template.setStandardError(standardError);
        }
        
        // Agent configuration
        template.setIdleMinutes(idleMinutes);
        template.setRunOnce(runOnce);
        template.setKeepJobOnFailure(keepJobOnFailure);
        
        return template;
    }
    
    /**
     * Apply JSON configuration to template.
     * Supports property names matching SlurmJobTemplate setters.
     */
    private void applyJsonConfiguration(@NonNull SlurmJobTemplate template, @NonNull String jsonString) {
        try {
            net.sf.json.JSONObject jsonConfig = net.sf.json.JSONObject.fromObject(jsonString);
            
            // Core SLURM fields
            if (jsonConfig.has("partition")) {
                template.setPartition(jsonConfig.getString("partition"));
            }
            if (jsonConfig.has("workingDir")) {
                template.setCurrentWorkingDirectory(jsonConfig.getString("workingDir"));
            }
            if (jsonConfig.has("cpus")) {
                template.setCpusPerTask(jsonConfig.getInt("cpus"));
            }
            if (jsonConfig.has("memory")) {
                template.setMemoryPerNode(parseMemoryToMB(jsonConfig.getString("memory")));
            }
            if (jsonConfig.has("time")) {
                template.setTimeLimit(parseTimeToMinutes(jsonConfig.getString("time")));
            }
            if (jsonConfig.has("gres")) {
                template.setTresPerJob(jsonConfig.getString("gres"));
            }
            if (jsonConfig.has("account")) {
                template.setAccount(jsonConfig.getString("account"));
            }
            if (jsonConfig.has("qos")) {
                template.setQos(jsonConfig.getString("qos"));
            }
            if (jsonConfig.has("reservation")) {
                template.setReservation(jsonConfig.getString("reservation"));
            }
            if (jsonConfig.has("constraints")) {
                template.setConstraints(jsonConfig.getString("constraints"));
            }
            if (jsonConfig.has("prefer")) {
                template.setPrefer(jsonConfig.getString("prefer"));
            }
            if (jsonConfig.has("nodes")) {
                template.setNodes(jsonConfig.getString("nodes"));
            }
            if (jsonConfig.has("tasks")) {
                template.setTasks(jsonConfig.getInt("tasks"));
            }
            if (jsonConfig.has("tasksPerNode")) {
                template.setTasksPerNode(jsonConfig.getInt("tasksPerNode"));
            }
            if (jsonConfig.has("ntasksPerTres")) {
                template.setNtasksPerTres(jsonConfig.getInt("ntasksPerTres"));
            }
            
            // Container support (basic - Pyxis would need nested object)
            if (jsonConfig.has("containerImage")) {
                PyxisConfig pyxisConfig = new PyxisConfig();
                pyxisConfig.setContainerImage(jsonConfig.getString("containerImage"));
                if (jsonConfig.has("containerMounts")) {
                    pyxisConfig.setContainerMounts(jsonConfig.getString("containerMounts"));
                }
                if (jsonConfig.has("containerWorkdir")) {
                    pyxisConfig.setContainerWorkdir(jsonConfig.getString("containerWorkdir"));
                }
                if (jsonConfig.has("containerMountHome")) {
                    pyxisConfig.setContainerMountHome(jsonConfig.getBoolean("containerMountHome"));
                }
                template.setPyxis(pyxisConfig);
            }
            
            // I/O
            if (jsonConfig.has("standardOutput")) {
                template.setStandardOutput(jsonConfig.getString("standardOutput"));
            }
            if (jsonConfig.has("standardError")) {
                template.setStandardError(jsonConfig.getString("standardError"));
            }
            
            LOGGER.fine("Successfully applied JSON configuration to template");
            
        } catch (Exception e) {
            LOGGER.warning("Failed to parse JSON configuration: " + e.getMessage());
            // Continue with other configuration - don't fail the build
        }
    }
    
    /**
     * Parse memory string to megabytes.
     * Supports formats: "32G", "4096M", "1024" (assumed MB)
     */
    private Long parseMemoryToMB(String memory) {
        if (memory == null || memory.trim().isEmpty()) {
            return null;
        }
        
        memory = memory.trim().toUpperCase();
        
        try {
            if (memory.endsWith("G") || memory.endsWith("GB")) {
                String num = memory.replaceAll("[GB]", "");
                return Long.parseLong(num) * 1024;
            } else if (memory.endsWith("M") || memory.endsWith("MB")) {
                String num = memory.replaceAll("[MB]", "");
                return Long.parseLong(num);
            } else if (memory.endsWith("K") || memory.endsWith("KB")) {
                String num = memory.replaceAll("[KB]", "");
                return Long.parseLong(num) / 1024;
            } else {
                // Assume MB if no unit
                return Long.parseLong(memory);
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("Could not parse memory value: " + memory);
            return null;
        }
    }
    
    /**
     * Parse time string to minutes.
     * Supports formats: "02:00:00" (HH:MM:SS), "120" (minutes), "2h", "30m"
     */
    private Integer parseTimeToMinutes(String time) {
        if (time == null || time.trim().isEmpty()) {
            return null;
        }
        
        time = time.trim().toLowerCase();
        
        try {
            // HH:MM:SS or MM:SS format
            if (time.contains(":")) {
                String[] parts = time.split(":");
                if (parts.length == 3) {
                    // HH:MM:SS
                    int hours = Integer.parseInt(parts[0]);
                    int minutes = Integer.parseInt(parts[1]);
                    int seconds = Integer.parseInt(parts[2]);
                    return hours * 60 + minutes + (seconds > 0 ? 1 : 0);  // Round up if seconds
                } else if (parts.length == 2) {
                    // MM:SS
                    int minutes = Integer.parseInt(parts[0]);
                    int seconds = Integer.parseInt(parts[1]);
                    return minutes + (seconds > 0 ? 1 : 0);
                }
            }
            
            // Hour format: "2h"
            if (time.endsWith("h")) {
                String num = time.substring(0, time.length() - 1);
                return Integer.parseInt(num) * 60;
            }
            
            // Minute format: "30m"
            if (time.endsWith("m")) {
                String num = time.substring(0, time.length() - 1);
                return Integer.parseInt(num);
            }
            
            // Assume minutes if just a number
            return Integer.parseInt(time);
            
        } catch (NumberFormatException e) {
            LOGGER.warning("Could not parse time value: " + time);
            return null;
        }
    }
    
    // ====================
    // Getters and Setters
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
    
    @DataBoundSetter
    public void setLabel(String label) {
        this.label = Util.fixEmpty(label);
    }
    
    @CheckForNull
    public String getInheritFrom() {
        return inheritFrom;
    }
    
    @DataBoundSetter
    public void setInheritFrom(String inheritFrom) {
        this.inheritFrom = Util.fixEmpty(inheritFrom);
    }
    
    @CheckForNull
    public String getJson() {
        return json;
    }
    
    @DataBoundSetter
    public void setJson(String json) {
        this.json = Util.fixEmpty(json);
    }
    
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
    
    @CheckForNull
    public PyxisConfig getPyxis() {
        return pyxis;
    }
    
    @DataBoundSetter
    public void setPyxis(PyxisConfig pyxis) {
        this.pyxis = pyxis;
    }
    
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
    
    @Extension
    @Symbol("slurmJobTemplate")
    public static class DescriptorImpl extends StepDescriptor {
        
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of();
        }
        
        @Override
        public String getFunctionName() {
            return "slurmJobTemplate";
        }
        
        @NonNull
        @Override
        public String getDisplayName() {
            return "SLURM Job Template";
        }
        
        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
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
