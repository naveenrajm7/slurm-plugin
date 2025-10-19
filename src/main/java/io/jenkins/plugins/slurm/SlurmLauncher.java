package io.jenkins.plugins.slurm;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.slurm.client.model.V0042JobDescMsg;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/**
 * Launcher for SLURM agents that submits jobs via the SLURM REST API.
 * The agent connects back to Jenkins using JNLP.
 */
public class SlurmLauncher extends JNLPLauncher {
    private static final Logger LOGGER = Logger.getLogger(SlurmLauncher.class.getName());
    
    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {
        LOGGER.info("=== SLURM Launcher: launch() called for computer: " + computer.getName() + " ===");
        
        if (!(computer instanceof SlurmComputer)) {
            throw new IllegalArgumentException("Computer must be an instance of SlurmComputer");
        }
        
        SlurmComputer slurmComputer = (SlurmComputer) computer;
        SlurmAgent agent = slurmComputer.getNode();
        
        if (agent == null) {
            LOGGER.severe("Agent is null for computer: " + computer.getName());
            throw new IllegalStateException("Agent is null");
        }
        
        LOGGER.info("Launching SLURM agent: " + agent.getNodeName());
        listener.getLogger().println("Launching SLURM agent: " + agent.getNodeName());
        
        try {
            // Get the cloud
            SlurmCloud cloud = agent.getSlurmCloud();
            LOGGER.info("Got cloud: " + cloud.name);
            
            // Get the template by ID
            SlurmJobTemplate template = cloud.getTemplateById(agent.getTemplateId());
            if (template == null) {
                LOGGER.severe("Template not found with ID: " + agent.getTemplateId());
                throw new IllegalStateException("Template not found with ID: " + agent.getTemplateId());
            }
            
            LOGGER.info("Using template: " + template.getName());
            listener.getLogger().println("Using template: " + template.getName());
            
            // Set the launching state
            slurmComputer.setLaunching(true);
            LOGGER.info("Set launching state to true");
            
            try {
                // Build the job description
                LOGGER.info("Building SLURM job description...");
                SlurmJobBuilder builder = new SlurmJobBuilder(
                    template,
                    agent.getNodeName(),
                    getJenkinsUrl(cloud),
                    getAgentSecret(slurmComputer)
                );
                V0042JobDescMsg jobDesc = builder.build();
                LOGGER.info("Job description built successfully");
                
                // Submit the job
                LOGGER.info("Submitting job to SLURM...");
                listener.getLogger().println("Submitting job to SLURM...");
                String jobId = cloud.submitJob(jobDesc, listener);
                
                // Store the job ID in the agent
                agent.setSlurmJobId(jobId);
                LOGGER.info("SLURM job submitted with ID: " + jobId);
                listener.getLogger().println("SLURM job submitted with ID: " + jobId);
                
                // Wait for the agent to connect via JNLP
                LOGGER.info("Waiting for agent to connect via WebSocket/JNLP...");
                listener.getLogger().println("Waiting for agent to connect via WebSocket/JNLP...");
                super.launch(computer, listener);
                
            } finally {
                slurmComputer.setLaunching(false);
                LOGGER.info("Set launching state to false");
            }
            
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Interrupted while launching SLURM agent: " + agent.getNodeName(), e);
            listener.error("Launch interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Launch interrupted", e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to launch SLURM agent: " + agent.getNodeName(), e);
            listener.error("Failed to launch SLURM agent: " + e.getMessage());
            throw new RuntimeException("Failed to launch SLURM agent", e);
        }
    }
    
    /**
     * Gets the Jenkins URL for agent connection.
     * Priority:
     * 1. Cloud configuration jenkinsUrl
     * 2. JenkinsLocationConfiguration URL
     * 3. Jenkins root URL
     * 4. Localhost fallback (for development)
     */
    private String getJenkinsUrl(SlurmCloud cloud) {
        // 1. Check cloud configuration
        if (cloud.getJenkinsUrl() != null && !cloud.getJenkinsUrl().trim().isEmpty()) {
            LOGGER.info("Using Jenkins URL from cloud configuration: " + cloud.getJenkinsUrl());
            return cloud.getJenkinsUrl();
        }
        
        // 2. Check JenkinsLocationConfiguration
        JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
        if (config != null) {
            String url = config.getUrl();
            if (url != null && !url.isEmpty()) {
                LOGGER.info("Using Jenkins URL from JenkinsLocationConfiguration: " + url);
                return url;
            }
        }
        
        // 3. Check Jenkins root URL
        Jenkins jenkins = Jenkins.get();
        String rootUrl = jenkins.getRootUrl();
        if (rootUrl != null && !rootUrl.isEmpty()) {
            LOGGER.info("Using Jenkins root URL: " + rootUrl);
            return rootUrl;
        }
        
        // 4. Development fallback - use localhost with default port
        String fallbackUrl = "http://localhost:8080/jenkins/";
        LOGGER.warning("Jenkins URL not configured anywhere - using fallback: " + fallbackUrl);
        LOGGER.warning("Please configure Jenkins URL in: Manage Jenkins > System > Jenkins Location OR in the SLURM Cloud configuration");
        return fallbackUrl;
    }
    
    /**
     * Gets the JNLP secret for the agent.
     */
    private String getAgentSecret(SlurmComputer computer) {
        String secret = computer.getJnlpMac();
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("Failed to get JNLP secret for agent");
        }
        return secret;
    }
    
    @Extension
    public static final class DescriptorImpl extends Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Launch SLURM agent";
        }
    }
}
