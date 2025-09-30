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
        if (!(computer instanceof SlurmComputer)) {
            throw new IllegalArgumentException("Computer must be an instance of SlurmComputer");
        }
        
        SlurmComputer slurmComputer = (SlurmComputer) computer;
        SlurmAgent agent = slurmComputer.getNode();
        
        if (agent == null) {
            throw new IllegalStateException("Agent is null");
        }
        
        listener.getLogger().println("Launching SLURM agent: " + agent.getNodeName());
        
        try {
            // Get the cloud
            SlurmCloud cloud = agent.getSlurmCloud();
            
            // Get the template
            SlurmJobTemplate template = cloud.getTemplate(agent.getTemplateId());
            if (template == null) {
                throw new IllegalStateException("Template not found: " + agent.getTemplateId());
            }
            
            listener.getLogger().println("Using template: " + template.getName());
            
            // Set the launching state
            slurmComputer.setLaunching(true);
            
            try {
                // Build the job description
                SlurmJobBuilder builder = new SlurmJobBuilder(
                    template,
                    agent.getNodeName(),
                    getJenkinsUrl(),
                    getAgentSecret(slurmComputer)
                );
                V0042JobDescMsg jobDesc = builder.build();
                
                // Submit the job
                listener.getLogger().println("Submitting job to SLURM...");
                String jobId = cloud.submitJob(jobDesc, listener);
                
                // Store the job ID in the agent
                agent.setSlurmJobId(jobId);
                listener.getLogger().println("SLURM job submitted with ID: " + jobId);
                
                // Wait for the agent to connect via JNLP
                listener.getLogger().println("Waiting for agent to connect via JNLP...");
                super.launch(computer, listener);
                
            } finally {
                slurmComputer.setLaunching(false);
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
     */
    private String getJenkinsUrl() {
        JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
        if (config == null) {
            throw new IllegalStateException("JenkinsLocationConfiguration is null");
        }
        
        String url = config.getUrl();
        if (url == null || url.isEmpty()) {
            // Fallback to root URL if location is not configured
            Jenkins jenkins = Jenkins.get();
            url = jenkins.getRootUrl();
            if (url == null || url.isEmpty()) {
                throw new IllegalStateException("Jenkins URL is not configured");
            }
        }
        
        return url;
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
