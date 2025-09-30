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
        
        // If no specific template found, create a default one
        LOGGER.info("No matching template found for label " + 
                   (label != null ? label.getName() : "none") + ", using default");
        return SlurmJobTemplateUtils.createDefaultTemplate(this);
    }
    
    public Collection<PlannedNode> provision(@CheckForNull Cloud.CloudState state,
                                           @NonNull Label label,
                                           int excessWorkload) {
        LOGGER.info("Slurm Cloud: Provision request for label=" + label + 
                   ", excessWorkload=" + excessWorkload);
        
        // Check if we can provision more agents
        int currentAgents = getCurrentAgentCount();
        if (currentAgents >= maxAgents) {
            LOGGER.info("Slurm Cloud: Cannot provision - at maximum agent limit (" + maxAgents + ")");
            return Collections.emptyList();
        }
        
        // Find appropriate job template for this label
        SlurmJobTemplate jobTemplate = getJobTemplateFor(label);
        LOGGER.info("Slurm Cloud: Using job template: " + jobTemplate.getName() + 
                   " for label: " + (label != null ? label.getName() : "none"));
        
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
        
        // TODO: Implement actual Slurm job submission and agent creation
        // For now, return a mock planned node
        return new PlannedNode(agentName, 
                              java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                                  try {
                                      LOGGER.info("Slurm Cloud: Creating agent " + agentName + 
                                                 " with template " + jobTemplate.getName());
                                      
                                      // This is where we would:
                                      // 1. Generate Slurm script using jobTemplate.generateSbatchScript(agentName)
                                      // 2. Submit job to Slurm cluster
                                      // 3. Wait for job to start
                                      // 4. Create SlurmAgent instance
                                      // 5. Add agent to Jenkins
                                      
                                      // For now, simulate with a delay
                                      Thread.sleep(5000);
                                      
                                      // Create a mock agent (this should be real SlurmAgent creation)
                                      LOGGER.info("Slurm Cloud: Mock agent " + agentName + " created successfully");
                                      return null; // Should return actual SlurmAgent instance
                                      
                                  } catch (Exception e) {
                                      LOGGER.severe("Failed to create Slurm agent " + agentName + ": " + e.getMessage());
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
        // Check if we have any templates that can handle this label
        List<SlurmJobTemplate> templates = SlurmJobTemplateUtils.getTemplatesFor(this, label);
        
        if (templates.isEmpty()) {
            LOGGER.fine("No templates available for label: " + 
                       (label != null ? label.getName() : "none"));
            // Still return true to allow default template creation
            return true;
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
         */
        private String testSlurmConnection(String apiUrl, String credentialsId) throws Exception {
            // Normalize API URL - ensure it doesn't end with slash
            String baseUrl = apiUrl.replaceAll("/+$", "");
            
            LOGGER.info("Testing Slurm connection to: " + baseUrl);
            
            // Retrieve JWT token from credentials
            String authToken = getAuthTokenFromCredentials(credentialsId);
            if (authToken == null || authToken.trim().isEmpty()) {
                LOGGER.warning("No authentication token provided - connection may fail");
            }
            
            try {
                SlurmClient client = new SlurmClient(baseUrl, authToken);
                
                // Test ping and get essential controller information
                SlurmPingInfo slurmInfo = client.getSlurmInfo();
                
                if (slurmInfo != null) {
                    return String.format("Connected to SLURM controller '%s' (v%s, cluster: %s) at %s", 
                                       slurmInfo.getHostname(), slurmInfo.getVersion(), 
                                       slurmInfo.getCluster(), baseUrl);
                } else {
                    throw new Exception("Ping failed - no response from SLURM controller");
                }
                                   
            } catch (Exception e) {
                LOGGER.warning("Slurm connection test failed: " + e.getMessage());
                throw new Exception("Failed to connect to SLURM REST API at " + baseUrl + ". Error: " + e.getMessage());
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