package io.jenkins.plugins.slurm.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
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
 * Pipeline step to create a Slurm job template context.
 * 
 * This step creates a temporary Slurm job template from declarative configuration
 * and provides it as context for nested pipeline steps.
 * 
 * Example usage with REST API format:
 * <pre>
 * slurmJobTemplate(
 *   cloud: 'my-cluster',
 *   json: '''
 *   {
 *     "job": {
 *       "partition": "gpu",
 *       "cpus_per_task": 16,
 *       "memory_per_node": {"set": true, "number": 32768},
 *       "tres_per_job": "gres/gpu:gfx1030:1",
 *       "required_nodes": ["node1", "node2"]
 *     },
 *     "pyxis": {
 *       "containerImage": "/path/to/image.sqsh",
 *       "containerMountHome": true
 *     }
 *   }
 *   '''
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
    
    // ObjectMapper for JSON deserialization
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
    
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
    
    // Core Slurm fields (mirroring SlurmJobTemplate)
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
    private int idleMinutes = 1;
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
        boolean hasAgentSettings = false;
        if (!StringUtils.isEmpty(json)) {
            LOGGER.fine("Applying JSON configuration");
            hasAgentSettings = applyJsonConfiguration(template, json);
        }
        
        // Then apply individual properties (which override JSON if both specified)
        if (!StringUtils.isEmpty(label)) {
            template.setLabel(label);
        }
        
        // Core Slurm fields
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
        
        // Agent configuration - only apply if not set by JSON agent_settings
        // (JSON agent_settings takes precedence over step defaults)
        if (!hasAgentSettings) {
            // No agent_settings in JSON, use step's defaults
            template.setIdleMinutes(idleMinutes);
            template.setRunOnce(runOnce);
            template.setKeepJobOnFailure(keepJobOnFailure);
        }
        
        // CRITICAL: For pipeline temporary templates, ALWAYS enforce instanceCap = 1
        // This prevents Jenkins from provisioning multiple agents for a single pipeline stage
        // regardless of what user specifies in agent_settings.instance_cap
        // (instance_cap in JSON is meant for permanent cloud templates, not pipeline templates)
        template.setInstanceCap(1);
        
        return template;
    }
    
    /**
     * Apply JSON configuration to template.
     * 
     * Uses REST API format matching Slurm REST API job_desc_msg structure:
     * {
     *   "agent_settings": {
     *     "idle_minutes": 1,
     *     "run_once": true,
     *     ...
     *   },
     *   "job": {
     *     "partition": "gpu",
     *     "cpus_per_task": 16,
     *     "memory_per_node": {"set": true, "number": 32768},
     *     "required_nodes": ["node1", "node2"],
     *     ...
     *   },
     *   "pyxis": {
     *     "container_image": "/path/to/image.sqsh",
     *     "container_mount_home": true,
     *     ...
     *   }
     * }
     * 
     * @return true if agent_settings was provided in JSON, false otherwise
     */
    private boolean applyJsonConfiguration(@NonNull SlurmJobTemplate template, @NonNull String jsonString) {
        boolean hasAgentSettings = false;
        try {
            net.sf.json.JSONObject jsonConfig = net.sf.json.JSONObject.fromObject(jsonString);
            
            // REST API format requires "job" key
            if (!jsonConfig.has("job")) {
                throw new IllegalArgumentException(
                    "JSON must have 'job' key matching Slurm REST API format. " +
                    "Example: {\"job\": {\"partition\": \"gpu\", \"cpus_per_task\": 16}, \"pyxis\": {...}}"
                );
            }
            
            LOGGER.fine("Parsing REST API format (job_desc_msg structure)");
            net.sf.json.JSONObject jobConfig = jsonConfig.getJSONObject("job");
            
            // Use ObjectMapper to deserialize job config into a temporary template
            // This handles type conversions and field mapping automatically
            String jobJsonString = jobConfig.toString();
            SlurmJobTemplate tempTemplate = OBJECT_MAPPER.readValue(jobJsonString, SlurmJobTemplate.class);
            
            // Copy non-null fields from temp template to actual template
            copyNonNullFields(tempTemplate, template);
            
            // Parse agent_settings (Jenkins-specific agent management)
            if (jsonConfig.has("agent_settings")) {
                hasAgentSettings = true;
                net.sf.json.JSONObject agentSettings = jsonConfig.getJSONObject("agent_settings");
                
                if (agentSettings.has("idle_minutes")) {
                    template.setIdleMinutes(agentSettings.getInt("idle_minutes"));
                    LOGGER.fine("Set idle_minutes: " + agentSettings.getInt("idle_minutes"));
                }
                if (agentSettings.has("run_once")) {
                    template.setRunOnce(agentSettings.getBoolean("run_once"));
                    LOGGER.fine("Set run_once: " + agentSettings.getBoolean("run_once"));
                }
                if (agentSettings.has("keep_job_on_failure")) {
                    template.setKeepJobOnFailure(agentSettings.getBoolean("keep_job_on_failure"));
                    LOGGER.fine("Set keep_job_on_failure: " + agentSettings.getBoolean("keep_job_on_failure"));
                }
                if (agentSettings.has("instance_cap")) {
                    template.setInstanceCap(agentSettings.getInt("instance_cap"));
                    LOGGER.fine("Set instance_cap: " + agentSettings.getInt("instance_cap"));
                }
            }
            
            // Parse pyxis configuration (plugin-specific, not part of Slurm REST API)
            if (jsonConfig.has("pyxis")) {
                String pyxisJsonString = jsonConfig.getJSONObject("pyxis").toString();
                PyxisConfig pyxisConfig = OBJECT_MAPPER.readValue(pyxisJsonString, PyxisConfig.class);
                template.setPyxis(pyxisConfig);
                LOGGER.fine("Applied PyxisConfig using ObjectMapper");
            }
            
            LOGGER.fine("Successfully applied JSON configuration to template");
            
        } catch (UnrecognizedPropertyException e) {
            String errorMsg = String.format(
                "Unknown field '%s' in JSON configuration. Check spelling and refer to Slurm REST API documentation.",
                e.getPropertyName()
            );
            LOGGER.severe(errorMsg);
            throw new IllegalArgumentException(errorMsg, e);
        } catch (JsonMappingException e) {
            String errorMsg = "Invalid JSON configuration: " + e.getOriginalMessage();
            LOGGER.severe(errorMsg);
            throw new IllegalArgumentException(errorMsg, e);
        } catch (Exception e) {
            String errorMsg = "Failed to parse JSON configuration: " + e.getMessage();
            LOGGER.severe(errorMsg);
            throw new IllegalArgumentException(errorMsg, e);
        }
        
        return hasAgentSettings;
    }
    
    /**
     * Copy non-null fields from source template to destination template.
     * This allows JSON configuration to override only specified fields.
     */
    private void copyNonNullFields(SlurmJobTemplate source, SlurmJobTemplate dest) {
        if (source.getPartition() != null && !source.getPartition().isEmpty()) {
            dest.setPartition(source.getPartition());
            LOGGER.fine("Set partition: " + source.getPartition());
        }
        if (source.getCurrentWorkingDirectory() != null && !source.getCurrentWorkingDirectory().isEmpty()) {
            dest.setCurrentWorkingDirectory(source.getCurrentWorkingDirectory());
        }
        if (source.getCpusPerTask() != null) {
            dest.setCpusPerTask(source.getCpusPerTask());
        }
        if (source.getMemoryPerNode() != null) {
            dest.setMemoryPerNode(source.getMemoryPerNode());
        }
        if (source.getTimeLimit() != null) {
            dest.setTimeLimit(source.getTimeLimit());
        }
        if (source.getTresPerJob() != null && !source.getTresPerJob().isEmpty()) {
            dest.setTresPerJob(source.getTresPerJob());
        }
        if (source.getTresPerNode() != null && !source.getTresPerNode().isEmpty()) {
            dest.setTresPerNode(source.getTresPerNode());
        }
        if (source.getTresPerTask() != null && !source.getTresPerTask().isEmpty()) {
            dest.setTresPerTask(source.getTresPerTask());
        }
        if (source.getAccount() != null && !source.getAccount().isEmpty()) {
            dest.setAccount(source.getAccount());
        }
        if (source.getQos() != null && !source.getQos().isEmpty()) {
            dest.setQos(source.getQos());
        }
        if (source.getReservation() != null && !source.getReservation().isEmpty()) {
            dest.setReservation(source.getReservation());
        }
        if (source.getConstraints() != null && !source.getConstraints().isEmpty()) {
            dest.setConstraints(source.getConstraints());
        }
        if (source.getRequiredNodes() != null && !source.getRequiredNodes().isEmpty()) {
            dest.setRequiredNodes(source.getRequiredNodes());
            LOGGER.fine("Set required_nodes: " + source.getRequiredNodes());
        }
        if (source.getExcludedNodes() != null && !source.getExcludedNodes().isEmpty()) {
            dest.setExcludedNodes(source.getExcludedNodes());
            LOGGER.fine("Set excluded_nodes: " + source.getExcludedNodes());
        }
        if (source.getPrefer() != null && !source.getPrefer().isEmpty()) {
            dest.setPrefer(source.getPrefer());
        }
        if (source.getNodes() != null && !source.getNodes().isEmpty()) {
            dest.setNodes(source.getNodes());
        }
        if (source.getMinimumNodes() != null) {
            dest.setMinimumNodes(source.getMinimumNodes());
        }
        if (source.getMaximumNodes() != null) {
            dest.setMaximumNodes(source.getMaximumNodes());
        }
        if (source.getTasks() != null) {
            dest.setTasks(source.getTasks());
        }
        if (source.getTasksPerNode() != null) {
            dest.setTasksPerNode(source.getTasksPerNode());
        }
        if (source.getNtasksPerTres() != null) {
            dest.setNtasksPerTres(source.getNtasksPerTres());
        }
        if (source.getStandardOutput() != null && !source.getStandardOutput().isEmpty()) {
            dest.setStandardOutput(source.getStandardOutput());
        }
        if (source.getStandardError() != null && !source.getStandardError().isEmpty()) {
            dest.setStandardError(source.getStandardError());
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
            return "Slurm Job Template";
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
