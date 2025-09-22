package io.jenkins.plugins.slurm;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * SLURM Cloud implementation for Jenkins.
 * 
 * This class represents a SLURM cluster as a Jenkins cloud provider,
 * allowing Jenkins to dynamically provision build agents by submitting
 * jobs to the SLURM workload manager.
 */
public class SlurmCloud extends AbstractCloudImpl {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmCloud.class.getName());
    
    private final String slurmControllerHost;
    private final int slurmControllerPort;
    private final String credentialsId;
    private final String defaultPartition;
    private final int maxAgents;
    private final int agentTimeoutMinutes;
    private List<SlurmJobTemplate> jobTemplates;
    
    @DataBoundConstructor
    public SlurmCloud(String name,
                      String slurmControllerHost,
                      int slurmControllerPort,
                      String credentialsId,
                      String defaultPartition,
                      int maxAgents,
                      int agentTimeoutMinutes) {
        super(name, String.valueOf(maxAgents > 0 ? maxAgents : 10));
        this.slurmControllerHost = slurmControllerHost;
        this.slurmControllerPort = slurmControllerPort > 0 ? slurmControllerPort : 22;
        this.credentialsId = credentialsId;
        this.defaultPartition = defaultPartition;
        this.maxAgents = maxAgents > 0 ? maxAgents : 10;
        this.agentTimeoutMinutes = agentTimeoutMinutes > 0 ? agentTimeoutMinutes : 60;
        this.jobTemplates = new ArrayList<>();
    }
    
    // Getters for configuration values
    public String getSlurmControllerHost() {
        return slurmControllerHost;
    }
    
    public int getSlurmControllerPort() {
        return slurmControllerPort;
    }
    
    public String getCredentialsId() {
        return credentialsId;
    }
    
    public String getDefaultPartition() {
        return defaultPartition;
    }
    
    public int getMaxAgents() {
        return maxAgents;
    }
    
    public int getAgentTimeoutMinutes() {
        return agentTimeoutMinutes;
    }
    
    public List<SlurmJobTemplate> getJobTemplates() {
        return jobTemplates != null ? jobTemplates : new ArrayList<>();
    }
    
    @DataBoundSetter
    public void setJobTemplates(List<SlurmJobTemplate> jobTemplates) {
        this.jobTemplates = jobTemplates != null ? jobTemplates : new ArrayList<>();
    }
    
    /**
     * Finds a suitable job template for the given label.
     */
    public SlurmJobTemplate getJobTemplateFor(@CheckForNull Label label) {
        String labelString = label != null ? label.getName() : null;
        
        // Look for a matching job template
        for (SlurmJobTemplate template : getJobTemplates()) {
            if (template.canTake(labelString)) {
                return template;
            }
        }
        
        // If no specific template found, create a default one
        SlurmJobTemplate defaultTemplate = new SlurmJobTemplate();
        defaultTemplate.setName("default");
        defaultTemplate.setPartition(defaultPartition);
        return defaultTemplate;
    }
    
    public Collection<PlannedNode> provision(@CheckForNull Cloud.CloudState state,
                                           @NonNull Label label,
                                           int excessWorkload) {
        LOGGER.info("SLURM Cloud: Provision request for label=" + label + 
                   ", excessWorkload=" + excessWorkload);
        
        // Check if we can provision more agents
        int currentAgents = getCurrentAgentCount();
        if (currentAgents >= maxAgents) {
            LOGGER.info("SLURM Cloud: Cannot provision - at maximum agent limit (" + maxAgents + ")");
            return Collections.emptyList();
        }
        
        // Find appropriate job template for this label
        SlurmJobTemplate jobTemplate = getJobTemplateFor(label);
        LOGGER.info("SLURM Cloud: Using job template: " + jobTemplate.getName() + 
                   " for label: " + (label != null ? label.getName() : "none"));
        
        // Check template-specific instance capacity
        int templateAgents = getCurrentTemplateAgentCount(jobTemplate);
        if (templateAgents >= jobTemplate.getInstanceCapStr()) {
            LOGGER.info("SLURM Cloud: Cannot provision - template '" + jobTemplate.getName() + 
                       "' at capacity limit (" + jobTemplate.getInstanceCapStr() + ")");
            return Collections.emptyList();
        }
        
        // Provision up to the requested excess workload, but respect limits
        int maxToProvision = Math.min(excessWorkload, 
                                    Math.min(maxAgents - currentAgents, 
                                           jobTemplate.getInstanceCapStr() - templateAgents));
        
        List<PlannedNode> plannedNodes = new ArrayList<>();
        for (int i = 0; i < maxToProvision; i++) {
            try {
                PlannedNode plannedNode = createPlannedNode(jobTemplate, label);
                if (plannedNode != null) {
                    plannedNodes.add(plannedNode);
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to create planned node for template " + jobTemplate.getName() + ": " + e.getMessage());
            }
        }
        
        LOGGER.info("SLURM Cloud: Planned " + plannedNodes.size() + " agents using template: " + jobTemplate.getName());
        return plannedNodes;
    }
    
    /**
     * Creates a planned node for the given job template and label.
     */
    private PlannedNode createPlannedNode(SlurmJobTemplate jobTemplate, @CheckForNull Label label) {
        String agentName = generateAgentName(jobTemplate);
        
        // TODO: Implement actual SLURM job submission and agent creation
        // For now, return a mock planned node
        return new PlannedNode(agentName, 
                              java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                                  try {
                                      LOGGER.info("SLURM Cloud: Creating agent " + agentName + 
                                                 " with template " + jobTemplate.getName());
                                      
                                      // This is where we would:
                                      // 1. Generate SLURM script using jobTemplate.generateSbatchScript(agentName)
                                      // 2. Submit job to SLURM cluster
                                      // 3. Wait for job to start
                                      // 4. Create SlurmAgent instance
                                      // 5. Add agent to Jenkins
                                      
                                      // For now, simulate with a delay
                                      Thread.sleep(5000);
                                      
                                      // Create a mock agent (this should be real SlurmAgent creation)
                                      LOGGER.info("SLURM Cloud: Mock agent " + agentName + " created successfully");
                                      return null; // Should return actual SlurmAgent instance
                                      
                                  } catch (Exception e) {
                                      LOGGER.severe("Failed to create SLURM agent " + agentName + ": " + e.getMessage());
                                      throw new RuntimeException(e);
                                  }
                              }), 
                              jobTemplate.getCpusPerTask()); // Use CPUs as approximate number of executors
    }
    
    /**
     * Generates a unique agent name based on the job template.
     */
    private String generateAgentName(SlurmJobTemplate jobTemplate) {
        return name + "-" + jobTemplate.getName() + "-" + System.currentTimeMillis();
    }
    
    /**
     * Gets the current number of agents using a specific job template.
     */
    private int getCurrentTemplateAgentCount(SlurmJobTemplate jobTemplate) {
        int count = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof SlurmAgent) {
                SlurmAgent slurmAgent = (SlurmAgent) node;
                // Check if this agent belongs to our cloud and uses this template
                // (This would need additional tracking in SlurmAgent)
                if (this.name.equals(slurmAgent.getCloudName())) {
                    count++; // Simplified - should check template match
                }
            }
        }
        return count;
    }
    
    public boolean canProvision(@CheckForNull Cloud.CloudState state, @NonNull Label label) {
        // TODO: Implement logic to determine if we can provision for this label
        // For now, accept all labels
        return true;
    }
    
    /**
     * Gets the current number of SLURM agents.
     */
    private int getCurrentAgentCount() {
        int count = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof SlurmAgent) {
                SlurmAgent slurmAgent = (SlurmAgent) node;
                if (this.name.equals(slurmAgent.getCloudName())) {
                    count++;
                }
            }
        }
        return count;
    }
    
    // Template management methods for web UI navigation
    
    /**
     * Gets the descriptor for SlurmJobTemplate for creating new templates.
     */
    public Descriptor<SlurmJobTemplate> getTemplateDescriptor() {
        return Jenkins.get().getDescriptor(SlurmJobTemplate.class);
    }
    
    /**
     * Checks if the current user has manage permission.
     */
    public boolean hasManagePermission() {
        return Jenkins.get().hasPermission(Jenkins.MANAGE);
    }
    
    /**
     * Creates a new job template.
     * Called from new.jelly form submission.
     */
    public void doCreate(org.kohsuke.stapler.StaplerRequest req, org.kohsuke.stapler.StaplerResponse rsp) 
            throws Exception {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        
        net.sf.json.JSONObject formData = req.getSubmittedForm();
        SlurmJobTemplate template = req.bindJSON(SlurmJobTemplate.class, formData);
        
        if (jobTemplates == null) {
            jobTemplates = new ArrayList<>();
        }
        
        // Validate template name is unique
        for (SlurmJobTemplate existing : jobTemplates) {
            if (existing.getName().equals(template.getName())) {
                throw new IllegalArgumentException("Template name must be unique: " + template.getName());
            }
        }
        
        jobTemplates.add(template);
        
        rsp.sendRedirect("templates");
    }
    
    /**
     * Gets a specific job template by name.
     * Used for individual template configuration pages.
     */
    public SlurmJobTemplate getTemplate(String name) {
        if (jobTemplates == null) return null;
        
        for (SlurmJobTemplate template : jobTemplates) {
            if (template.getName().equals(name)) {
                return template;
            }
        }
        return null;
    }
    
    /**
     * Provides access to templates via URLs like /cloud/cloudname/template/templatename
     * This is the method that will be called when accessing template URLs.
     */
    public Object getDynamic(String token) {
        if ("template".equals(token)) {
            return new TemplateUrlHandler();
        }
        return null;
    }
    
    /**
     * Inner class to handle template URL routing.
     */
    public class TemplateUrlHandler {
        public SlurmJobTemplate getDynamic(String templateName) {
            return getTemplate(templateName);
        }
    }
    
    @Extension
    @Symbol("slurm")
    public static class DescriptorImpl extends Descriptor<Cloud> {
        
        @Override
        public String getDisplayName() {
            return "SLURM";
        }
        
        public FormValidation doCheckSlurmControllerHost(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("SLURM controller hostname is required");
            }
            return FormValidation.ok();
        }
        
        public FormValidation doCheckSlurmControllerPort(@QueryParameter String value) {
            try {
                int port = Integer.parseInt(value);
                if (port <= 0 || port > 65535) {
                    return FormValidation.error("Port must be between 1 and 65535");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid port number");
            }
        }
        
        public FormValidation doCheckMaxAgents(@QueryParameter String value) {
            try {
                int max = Integer.parseInt(value);
                if (max <= 0) {
                    return FormValidation.error("Maximum agents must be greater than 0");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number");
            }
        }
        
        public ListBoxModel doFillCredentialsIdItems() {
            // TODO: Implement credentials dropdown
            ListBoxModel items = new ListBoxModel();
            items.add("Select credentials...", "");
            return items;
        }
    }
}