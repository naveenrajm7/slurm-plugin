package io.jenkins.plugins.slurm;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import net.sf.json.JSONArray;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmPingInfo;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Slurm Cloud implementation for Jenkins.
 * 
 * This class represents a Slurm cluster as a Jenkins cloud provider,
 * allowing Jenkins to dynamically provision build agents by communicating
 * with the Slurm REST API (slurmrestd) for job submission, monitoring, and cancellation.
 */
public class SlurmCloud extends AbstractCloudImpl {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmCloud.class.getName());
    
    private final String slurmRestApiUrl;
    private final String credentialsId;
    private final String defaultPartition;
    private final int maxAgents;
    private final int agentTimeoutMinutes;
    private String jenkinsUrl;  // Optional - if not set, will auto-detect
    private List<SlurmJobTemplate> jobTemplates;
    
    @DataBoundConstructor
    public SlurmCloud(String name,
                      String slurmRestApiUrl,
                      String credentialsId,
                      String defaultPartition,
                      int maxAgents,
                      int agentTimeoutMinutes) {
        super(name, String.valueOf(maxAgents > 0 ? maxAgents : 10));
        this.slurmRestApiUrl = slurmRestApiUrl != null && !slurmRestApiUrl.trim().isEmpty() ? 
                              slurmRestApiUrl : "http://localhost:6820";
        this.credentialsId = credentialsId;
        this.defaultPartition = defaultPartition;
        this.maxAgents = maxAgents > 0 ? maxAgents : 10;
        this.agentTimeoutMinutes = agentTimeoutMinutes > 0 ? agentTimeoutMinutes : 60;
        this.jenkinsUrl = null;  // Will be set via DataBoundSetter or auto-detected
        this.jobTemplates = new ArrayList<>();
    }
    
    // Getters for configuration values
    public String getSlurmRestApiUrl() {
        return slurmRestApiUrl;
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
    
    public String getJenkinsUrl() {
        return jenkinsUrl;
    }
    
    @DataBoundSetter
    public void setJenkinsUrl(String jenkinsUrl) {
        this.jenkinsUrl = jenkinsUrl;
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
     * Uses the template utility class to get filtered templates.
     */
    public SlurmJobTemplate getJobTemplateFor(@CheckForNull Label label) {
        // Use utility class to get filtered templates
        SlurmJobTemplate template = SlurmJobTemplateUtils.getTemplateByLabel(this, label);
        
        if (template != null) {
            return template;
        }
        
        // No default fallback - require explicit template configuration
        LOGGER.warning("No matching template found for label " + 
                      (label != null ? label.getName() : "none"));
        return null;
    }
    
    @Override
    public Collection<PlannedNode> provision(@NonNull Cloud.CloudState state,
                                           int excessWorkload) {
        // Extract label from state
        Label label = state.getLabel();
        
        LOGGER.info("Slurm Cloud: Provision request for label=" + label + 
                   ", excessWorkload=" + excessWorkload);
        
        // Find appropriate job template for this label
        SlurmJobTemplate jobTemplate = getJobTemplateFor(label);
        if (jobTemplate == null) {
            LOGGER.warning("Slurm Cloud: No template configured for label: " + 
                         (label != null ? label.getName() : "none"));
            return Collections.emptyList();
        }
        
        LOGGER.info("Slurm Cloud: Using job template: " + jobTemplate.getName() + 
                   " for label: " + (label != null ? label.getName() : "none"));
        
        // Check if we can provision more agents
        int currentAgents = getCurrentAgentCount();
        if (currentAgents >= maxAgents) {
            LOGGER.info("Slurm Cloud: Cannot provision - at maximum agent limit (" + maxAgents + ")");
            return Collections.emptyList();
        }
        
        // Check template-specific instance capacity
        int templateAgents = getCurrentTemplateAgentCount(jobTemplate);
        if (templateAgents >= jobTemplate.getInstanceCapStr()) {
            LOGGER.info("Slurm Cloud: Cannot provision - template '" + jobTemplate.getName() + 
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
        
        LOGGER.info("Slurm Cloud: Planned " + plannedNodes.size() + " agents using template: " + jobTemplate.getName());
        return plannedNodes;
    }
    
    /**
     * Creates a planned node for the given job template and label.
     */
    private PlannedNode createPlannedNode(SlurmJobTemplate jobTemplate, @CheckForNull Label label) {
        String agentName = generateAgentName(jobTemplate);
        
        return new PlannedNode(agentName, 
                              java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                                  try {
                                      LOGGER.info("Slurm Cloud: Creating agent " + agentName + 
                                                 " with template " + jobTemplate.getName());
                                      
                                      // 1. Create the SLURM launcher (no-arg constructor)
                                      SlurmLauncher launcher = new SlurmLauncher();
                                      
                                      // 2. Create retention strategy based on template configuration
                                      // CloudRetentionStrategy handles idle timeout:
                                      // - idleMinutes=0: Agent terminates immediately when idle (one-shot mode)
                                      // - idleMinutes>0: Agent waits idle period before termination (allows reuse)
                                      hudson.slaves.RetentionStrategy<?> retentionStrategy = 
                                          new hudson.slaves.CloudRetentionStrategy(jobTemplate.getIdleMinutes());
                                      
                                      if (jobTemplate.getIdleMinutes() == 0) {
                                          LOGGER.info("Using CloudRetentionStrategy with idleMinutes=0 (one-shot mode) for agent: " + agentName);
                                          LOGGER.info("Agent will terminate immediately when idle after build completes");
                                      } else if (jobTemplate.isRunOnce()) {
                                          LOGGER.info("Using CloudRetentionStrategy with idle minutes: " + jobTemplate.getIdleMinutes() + " (run-once mode)");
                                          LOGGER.info("Agent will be terminated after build completes and " + jobTemplate.getIdleMinutes() + " minute(s) idle timeout");
                                      } else {
                                          LOGGER.info("Using CloudRetentionStrategy with idle minutes: " + jobTemplate.getIdleMinutes() + " (reusable mode)");
                                          LOGGER.info("Agent can be reused for multiple builds within " + jobTemplate.getIdleMinutes() + " minute(s) idle period");
                                      }
                                      
                                      // 3. Create the SLURM agent with proper constructor parameters
                                      SlurmAgent agent = new SlurmAgent(
                                          agentName,                                    // name
                                          "SLURM agent from template " + jobTemplate.getName(),  // description
                                          jobTemplate.getCurrentWorkingDirectory(),      // remoteFS
                                          jobTemplate.getCpusPerTask(),                 // numExecutors
                                          jobTemplate.getNodeUsageMode(),               // mode
                                          jobTemplate.getLabel(),                       // labelString
                                          launcher,                                     // launcher
                                          retentionStrategy,                            // retentionStrategy
                                          new java.util.ArrayList<>(),                  // nodeProperties (empty)
                                          this.name,                                    // cloudName
                                          jobTemplate.getId(),                          // templateId
                                          jobTemplate.getPartition()                    // partition
                                      );
                                      
                                      // 4. Add agent to Jenkins
                                      Jenkins.get().addNode(agent);
                                      
                                      LOGGER.info("Slurm Cloud: Agent " + agentName + " created successfully");
                                      
                                      // 5. Get the computer and trigger the launcher to submit SLURM job
                                      hudson.model.Computer computer = agent.toComputer();
                                      if (computer != null) {
                                          LOGGER.info("Slurm Cloud: Triggering launcher for agent " + agentName);
                                          computer.connect(false);  // Trigger the launcher
                                      } else {
                                          LOGGER.warning("Slurm Cloud: Computer is null for agent " + agentName);
                                      }
                                      
                                      return agent;
                                      
                                  } catch (Exception e) {
                                      LOGGER.severe("Failed to create Slurm agent " + agentName + ": " + e.getMessage());
                                      e.printStackTrace();
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
                // Check if this agent belongs to our cloud and uses this specific template
                if (this.name.equals(slurmAgent.getCloudName()) && 
                    jobTemplate.getId().equals(slurmAgent.getTemplateId())) {
                    count++;
                }
            }
        }
        return count;
    }
    
    @Override
    public boolean canProvision(@NonNull Cloud.CloudState state) {
        // Get the label from the state
        Label label = state.getLabel();
        
        // Check if we have a template that can handle this label
        SlurmJobTemplate template = getJobTemplateFor(label);
        
        if (template == null) {
            LOGGER.fine("No template available for label: " + 
                       (label != null ? label.getName() : "none"));
            return false;
        }
        
        // Check if we're at capacity
        if (getCurrentAgentCount() >= maxAgents) {
            LOGGER.fine("Cannot provision - at maximum agent capacity");
            return false;
        }
        
        return true;
    }
    
    /**
     * Gets the current number of Slurm agents.
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
    
    /**
     * Submits a SLURM job via the REST API.
     * 
     * @param jobDesc The job description to submit
     * @param listener The task listener for logging
     * @return The SLURM job ID, or null if submission failed
     * @throws Exception if submission fails
     */
    public String submitJob(io.jenkins.plugins.slurm.client.model.V0042JobDescMsg jobDesc, 
                           hudson.model.TaskListener listener) throws Exception {
        
        LOGGER.info("Submitting SLURM job with name: " + jobDesc.getName());
        
        // Get the SLURM client for this cloud
        SlurmClient client = SlurmClientProvider.createClient(this);
        
        if (client == null) {
            throw new Exception("Failed to create SLURM client - check cloud configuration");
        }
        
        try {
            // Create job submit request
            io.jenkins.plugins.slurm.client.model.V0042JobSubmitReq submitReq = 
                new io.jenkins.plugins.slurm.client.model.V0042JobSubmitReq();
            submitReq.setJob(jobDesc);
            
            // Submit the job
            io.jenkins.plugins.slurm.client.model.V0042OpenapiJobSubmitResponse response = client.submitJob(submitReq);
            
            if (response == null) {
                throw new Exception("Job submission returned null response");
            }
            
            // Extract job ID from response
            String jobId = extractJobId(response);
            
            if (jobId != null && !jobId.isEmpty()) {
                LOGGER.info("SLURM job submitted successfully: " + jobId);
                listener.getLogger().println("Job submitted with ID: " + jobId);
                return jobId;
            } else {
                throw new Exception("Job submission succeeded but no job ID was returned");
            }
            
        } catch (Exception e) {
            LOGGER.severe("Failed to submit SLURM job: " + e.getMessage());
            listener.error("Failed to submit job: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Extracts the job ID from a job submission response.
     */
    private String extractJobId(io.jenkins.plugins.slurm.client.model.V0042OpenapiJobSubmitResponse response) {
        // The response should contain job_id in the result
        if (response.getJobId() != null) {
            return String.valueOf(response.getJobId());
        }
        
        // Check errors list for information
        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            LOGGER.warning("Job submission response contains errors");
            for (io.jenkins.plugins.slurm.client.model.V0042OpenapiError error : response.getErrors()) {
                LOGGER.warning("  Error: " + error.getError());
            }
        }
        
        return null;
    }
    
    /**
     * Cancels a SLURM job.
     * 
     * @param jobId The SLURM job ID to cancel
     * @param listener Optional task listener for logging
     */
    public void cancelJob(String jobId, @CheckForNull hudson.model.TaskListener listener) {
        if (jobId == null || jobId.isEmpty()) {
            LOGGER.warning("Cannot cancel job - no job ID provided");
            return;
        }
        
        LOGGER.info("Canceling SLURM job: " + jobId);
        
        try {
            SlurmClient client = SlurmClientProvider.createClient(this);
            
            if (client == null) {
                LOGGER.warning("Failed to get SLURM client for job cancellation");
                return;
            }
            
            client.cancelJob(jobId);
            
            LOGGER.info("SLURM job canceled: " + jobId);
            if (listener != null) {
                listener.getLogger().println("Canceled SLURM job: " + jobId);
            }
            
        } catch (Exception e) {
            LOGGER.warning("Failed to cancel SLURM job " + jobId + ": " + e.getMessage());
            if (listener != null) {
                listener.error("Failed to cancel job: " + e.getMessage());
            }
        }
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
     * Gets a specific job template by ID (UUID).
     * Used by agents to look up their template.
     */
    public SlurmJobTemplate getTemplateById(String templateId) {
        if (jobTemplates == null || templateId == null) return null;
        
        for (SlurmJobTemplate template : jobTemplates) {
            if (templateId.equals(template.getId())) {
                return template;
            }
        }
        return null;
    }
    
    /**
     * Removes a template from this cloud.
     * Called by the template's delete method.
     */
    public void removeTemplate(SlurmJobTemplate template) {
        if (jobTemplates != null) {
            jobTemplates.removeIf(t -> t.getId().equals(template.getId()));
        }
    }
    
    /**
     * Checks if the current user has manage permission.
     * Used by templates for permission checks.
     */
    public void checkManagePermission() {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
    }
    
    /**
     * Gets the URL for the templates page.
     * Used for redirects after template operations.
     */
    public String getTemplatesUrl() {
        return getUrl() + "templates";
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
            return "Slurm";
        }
        
        public FormValidation doCheckSlurmRestApiUrl(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Slurm REST API URL is required");
            }
            
            // Basic URL validation
            try {
                java.net.URL url = new java.net.URL(value);
                if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
                    return FormValidation.error("URL must use http or https protocol");
                }
                return FormValidation.ok();
            } catch (java.net.MalformedURLException e) {
                return FormValidation.error("Invalid URL format. Example: http://slurm-controller:6820");
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
        
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeEmptyValue();
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeEmptyValue();
                }
            }
            
            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, StringCredentials.class)
                    .includeCurrentValue(null);
        }
        
        /**
         * Test connection to Slurm REST API.
         * Similar to Kubernetes plugin's test connection functionality.
         */
        public FormValidation doTestConnection(@QueryParameter("slurmRestApiUrl") String slurmRestApiUrl,
                                             @QueryParameter("credentialsId") String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.MANAGE);
            
            if (slurmRestApiUrl == null || slurmRestApiUrl.trim().isEmpty()) {
                return FormValidation.error("Slurm REST API URL is required");
            }
            
            try {
                // Validate URL format first
                java.net.URL url = new java.net.URL(slurmRestApiUrl);
                if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
                    return FormValidation.error("URL must use http or https protocol");
                }
                
                // Test connection using ping endpoint
                String pingResult = testSlurmConnection(slurmRestApiUrl, credentialsId);
                return FormValidation.ok(pingResult);
                
            } catch (java.net.MalformedURLException e) {
                return FormValidation.error("Invalid URL format: " + e.getMessage());
            } catch (Exception e) {
                return FormValidation.error("Connection failed: " + e.getMessage());
            }
        }
        
        /**
         * Tests the connection to Slurm REST API using the ping endpoint with OpenAPI client.
         * Validates both REST API connectivity and JWT token authentication.
         */
        private String testSlurmConnection(String apiUrl, String credentialsId) throws Exception {
            // Normalize API URL - ensure it doesn't end with slash
            String baseUrl = apiUrl.replaceAll("/+$", "");
            
            LOGGER.info("Testing Slurm connection to: " + baseUrl);
            
            // Retrieve JWT token from credentials
            String authToken = getAuthTokenFromCredentials(credentialsId);
            if (authToken == null || authToken.trim().isEmpty()) {
                throw new Exception("No authentication token provided. Please configure JWT token credentials.");
            }
            
            try {
                SlurmClient client = new SlurmClient(baseUrl, authToken);
                
                // Test ping and get detailed controller information
                SlurmPingInfo slurmInfo = client.getSlurmInfo();
                
                if (slurmInfo == null) {
                    throw new Exception("Ping failed - no response from SLURM REST API. Check if slurmrestd is running.");
                }
                
                // Check if controller is actually responding
                if (slurmInfo.getResponding() == null || !slurmInfo.getResponding()) {
                    // REST API is up but controller is not responding - likely auth issue
                    StringBuilder errorMsg = new StringBuilder();
                    errorMsg.append("SLURM REST API is reachable but controller is not responding.\n\n");
                    
                    if (slurmInfo.getPinged() != null && !slurmInfo.getPinged().isEmpty()) {
                        errorMsg.append("Pinged: ").append(slurmInfo.getPinged()).append("\n\n");
                    }
                    
                    errorMsg.append("This usually indicates:\n");
                    errorMsg.append("• Invalid or expired JWT token\n");
                    errorMsg.append("• Insufficient token permissions\n");
                    errorMsg.append("• slurmctld service is down\n\n");
                    errorMsg.append("Please verify your JWT token credentials.");
                    
                    LOGGER.warning("Controller not responding: " + errorMsg.toString());
                    throw new Exception(errorMsg.toString());
                }
                
                // Successfully connected - build detailed status message
                StringBuilder statusMsg = new StringBuilder();
                statusMsg.append("✓ Successfully connected to SLURM controller\n\n");
                
                // Controller information
                if (slurmInfo.getHostname() != null) {
                    statusMsg.append("Hostname: ").append(slurmInfo.getHostname()).append("\n");
                }
                
                if (slurmInfo.getVersion() != null) {
                    statusMsg.append("Version: ").append(slurmInfo.getVersion()).append("\n");
                }
                
                if (slurmInfo.getCluster() != null) {
                    statusMsg.append("Cluster: ").append(slurmInfo.getCluster()).append("\n");
                }
                
                if (slurmInfo.getMode() != null) {
                    statusMsg.append("Mode: ").append(slurmInfo.getMode()).append("\n");
                }
                
                if (slurmInfo.getPrimary() != null) {
                    statusMsg.append("Primary Controller: ").append(slurmInfo.getPrimary() ? "Yes" : "No").append("\n");
                }
                
                if (slurmInfo.getLatency() != null) {
                    statusMsg.append("Latency: ").append(slurmInfo.getLatency()).append(" µs\n");
                }
                
                if (slurmInfo.getPinged() != null) {
                    statusMsg.append("Ping Result: ").append(slurmInfo.getPinged()).append("\n");
                }
                
                statusMsg.append("\nAPI URL: ").append(baseUrl);
                statusMsg.append("\nAuthentication: ✓ Valid JWT token");
                
                LOGGER.info("Slurm connection test successful");
                return statusMsg.toString();
                                   
            } catch (Exception e) {
                LOGGER.warning("Slurm connection test failed: " + e.getMessage());
                
                // Re-throw with better context if not already formatted
                if (e.getMessage().contains("controller is not responding") || 
                    e.getMessage().contains("no response from SLURM REST API") ||
                    e.getMessage().contains("No authentication token")) {
                    throw e;
                }
                
                throw new Exception("Failed to connect to SLURM REST API at " + baseUrl + 
                                  ".\n\nError: " + e.getMessage() + 
                                  "\n\nTroubleshooting:\n" +
                                  "• Verify slurmrestd is running and accessible\n" +
                                  "• Check network connectivity and firewall rules\n" +
                                  "• Ensure URL format is correct (http://host:6820)");
            }
        }
        
        /**
         * Retrieves authentication token from Jenkins credentials.
         * Looks up Secret Text credentials containing JWT token.
         */
        private String getAuthTokenFromCredentials(String credentialsId) {
            if (credentialsId == null || credentialsId.trim().isEmpty()) {
                LOGGER.warning("No credentials ID provided for Slurm authentication");
                return null;
            }
            
            try {
                // Use the lookupCredentials method instead
                List<StringCredentials> credentials = CredentialsProvider.lookupCredentials(
                    StringCredentials.class,
                    (Item) null,
                    ACL.SYSTEM,
                    Collections.<DomainRequirement>emptyList()
                );
                
                for (StringCredentials credential : credentials) {
                    if (credentialsId.equals(credential.getId())) {
                        Secret secret = credential.getSecret();
                        String token = Secret.toString(secret);
                        LOGGER.info("Successfully retrieved JWT token from credentials ID: " + credentialsId);
                        return token;
                    }
                }
                
                LOGGER.warning("Could not find Secret Text credentials with ID: " + credentialsId);
                return null;
                
            } catch (Exception e) {
                LOGGER.severe("Failed to retrieve credentials: " + e.getMessage());
                return null;
            }
        }
        

    }
}