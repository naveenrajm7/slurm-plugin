package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import hudson.slaves.OfflineCause;
import io.jenkins.plugins.slurm.client.model.JobDescMsg;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.ApiException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/**
 * Launcher for Slurm agents that submits jobs via the Slurm REST API.
 * The agent connects back to Jenkins using JNLP.
 * 
 * This launcher follows the Kubernetes plugin pattern:
 * - Submits the job once (no retry at launcher level)
 * - Actively polls job status while waiting for agent connection
 * - Fails fast if job enters a failed state
 * - Times out with clear error message
 * - Lets Jenkins provisioning strategy decide whether to retry
 */
public class SlurmLauncher extends JNLPLauncher {
    private static final Logger LOGGER = Logger.getLogger(SlurmLauncher.class.getName());
    
    // Timeout for waiting for agent to connect (5 minutes)
    private static final long AGENT_TIMEOUT_MS = 5 * 60 * 1000;
    
    // How often to check job status (5 seconds)
    private static final long STATUS_CHECK_INTERVAL_MS = 5000;
    
    // How often to log progress (30 seconds)
    private static final long PROGRESS_LOG_INTERVAL_MS = 30000;
    
    private volatile boolean launched = false;
    
    /**
     * Provisioning exception if any.
     * Following Kubernetes plugin pattern: stores the first failure to prevent retries.
     */
    @CheckForNull
    private transient Throwable problem;
    
    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {
        LOGGER.info("=== Slurm Launcher: launch() called for computer: " + computer.getName() + " ===");
        
        if (!(computer instanceof SlurmComputer)) {
            throw new IllegalArgumentException("Computer must be an instance of SlurmComputer");
        }
        
        SlurmComputer slurmComputer = (SlurmComputer) computer;
        computer.setAcceptingTasks(false);  // Kubernetes pattern: disable tasks until ready
        SlurmAgent agent = slurmComputer.getNode();
        
        if (agent == null) {
            LOGGER.severe("Agent is null for computer: " + computer.getName());
            throw new IllegalStateException("Agent is null");
        }
        
        // Kubernetes pattern: Check if previous launch failed permanently
        if (problem != null) {
            LOGGER.severe("Launch previously failed permanently for agent: " + agent.getNodeName() + 
                         " - " + problem.getMessage());
            listener.error("Agent launch previously failed: " + problem.getMessage());
            listener.error("Not retrying - please check agent configuration and Slurm logs");
            // Don't throw - just return and keep agent offline
            // This prevents the exception from bubbling up and triggering retry logic
            return;
        }
        
        // Kubernetes pattern: if already launched, just activate and return
        if (launched) {
            LOGGER.info("Agent already launched, activating: " + agent.getNodeName());
            computer.setAcceptingTasks(true);
            return;
        }
        
        LOGGER.info("Launching Slurm agent: " + agent.getNodeName());
        listener.getLogger().println("Launching Slurm agent: " + agent.getNodeName());
        
        SlurmCloud cloud = null;  // Declare in method scope for catch blocks
        try {
            // Get the cloud
            cloud = agent.getSlurmCloud();
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
                LOGGER.info("Building Slurm job description...");
                SlurmJobBuilder builder = new SlurmJobBuilder(
                    template,
                    agent.getNodeName(),
                    getJenkinsUrl(cloud),
                    getAgentSecret(slurmComputer)
                );
                JobDescMsg jobDesc = builder.build();
                LOGGER.info("Job description built successfully");
                
                // Submit the job
                LOGGER.info("Submitting job to Slurm...");
                listener.getLogger().println("Submitting job to Slurm...");
                String jobId = cloud.submitJob(jobDesc, listener);
                
                // Store the job ID in the agent
                agent.setSlurmJobId(jobId);
                LOGGER.info("Slurm job submitted with ID: " + jobId);
                listener.getLogger().println("Slurm job submitted with ID: " + jobId);
                
                // Wait for the agent to connect with active status checking
                LOGGER.info("Waiting for agent to connect via WebSocket/JNLP...");
                listener.getLogger().println("Waiting for agent to connect...");
                waitForAgentConnection(slurmComputer, agent, cloud, jobId, listener);
                
            } finally {
                slurmComputer.setLaunching(false);
                LOGGER.info("Set launching state to false");
            }
            
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Interrupted while launching Slurm agent: " + agent.getNodeName(), e);
            listener.error("Launch interrupted: " + e.getMessage());
            
            // Set problem field to prevent retries
            setProblem(e);
            
            // Cancel the Slurm job if it was submitted
            cancelJobOnFailure(agent, cloud, listener);
            
            // Kubernetes pattern: Cancel queue item to prevent infinite provisioning loop
            JobUtils.cancelQueueItemFor(agent, "Launch interrupted: " + e.getMessage());
            
            // Set computer offline
            String errorMsg = "Launch interrupted: " + e.getMessage();
            slurmComputer.setTemporarilyOffline(true, 
                new OfflineCause.UserCause(null, errorMsg));
            
            // Remove the node from Jenkins
            removeNodeOnFailure(agent);
            
            Thread.currentThread().interrupt();
            throw new RuntimeException("Launch interrupted", e);
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to launch Slurm agent: " + agent.getNodeName(), e);
            listener.error("Failed to launch Slurm agent: " + e.getMessage());
            
            // Set problem field to prevent retries
            setProblem(e);
            
            // Cancel the Slurm job if it was submitted
            cancelJobOnFailure(agent, cloud, listener);
            
            // Kubernetes pattern: Cancel queue item to prevent infinite provisioning loop
            JobUtils.cancelQueueItemFor(agent, "Launch failed: " + e.getMessage());
            
            // Set computer offline
            String errorMsg = "Launch failed: " + e.getMessage();
            slurmComputer.setTemporarilyOffline(true, 
                new OfflineCause.UserCause(null, errorMsg));
            
            // Remove the node from Jenkins
            removeNodeOnFailure(agent);
            
            throw new RuntimeException("Failed to launch Slurm agent", e);
        }
    }
    
    /**
     * Cancel the Slurm job when launcher fails.
     */
    private void cancelJobOnFailure(SlurmAgent agent, SlurmCloud cloud, TaskListener listener) {
        String jobId = agent.getSlurmJobId();
        if (jobId != null && cloud != null) {
            try {
                LOGGER.info("Canceling Slurm job " + jobId + " due to launch failure");
                cloud.cancelJob(jobId, listener);
                listener.getLogger().println("Canceled Slurm job: " + jobId);
            } catch (Exception cancelEx) {
                LOGGER.log(Level.WARNING, "Failed to cancel Slurm job " + jobId, cancelEx);
                listener.getLogger().println("Warning: Failed to cancel Slurm job " + jobId + ": " + cancelEx.getMessage());
            }
        }
    }
    
    /**
     * Remove the agent node from Jenkins on failure.
     * Kubernetes plugin pattern: use node.terminate() instead of manual removal.
     */
    private void removeNodeOnFailure(SlurmAgent agent) {
        try {
            agent.terminate();
            LOGGER.info("Terminated failed agent node: " + agent.getNodeName());
        } catch (IOException | InterruptedException removeEx) {
            LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
        }
    }
    
    
    /**
     * Wait for agent to connect with active status checking.
     * This follows the Kubernetes plugin pattern:
     * - Actively polls job status while waiting
     * - Fails fast if job enters a failed state
     * - Times out with clear error message
     * - Logs progress periodically
     */
    private void waitForAgentConnection(SlurmComputer computer, SlurmAgent agent, 
                                        SlurmCloud cloud, String jobId, 
                                        TaskListener listener) throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeout = startTime + AGENT_TIMEOUT_MS;
        long lastStatusCheck = 0;
        long lastProgressLog = startTime;
        boolean jobRunning = false;  // Track if job reached RUNNING state
        
        listener.getLogger().println("Waiting for agent to connect (timeout: " + 
            (AGENT_TIMEOUT_MS / 1000) + " seconds)...");
        
        SlurmClient client = null;
        try {
            client = SlurmClientProvider.createClient(cloud);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create Slurm client for status checking", e);
            listener.getLogger().println("Warning: Could not create Slurm client for status checking. " +
                "Will wait for agent connection without status monitoring.");
        }
        
        while (System.currentTimeMillis() < timeout) {
            // Check job status periodically
            if (client != null && System.currentTimeMillis() - lastStatusCheck >= STATUS_CHECK_INTERVAL_MS) {
                try {
                    String jobState = client.getJobState(jobId);
                    
                    if (jobState != null) {
                        // Kubernetes pattern: Check if job reached RUNNING state (like pod ready)
                        if (jobState.equals("RUNNING")) {
                            if (!jobRunning) {
                                jobRunning = true;
                                listener.getLogger().println("Slurm job " + jobId + " is now RUNNING");
                                LOGGER.info("Slurm job " + jobId + " reached RUNNING state");
                            }
                        }
                        
                        // Check if job is in a failed state
                        if (SlurmClient.isFailedState(jobState)) {
                            LOGGER.severe("FAIL-FAST: Job " + jobId + " entered FAILED state: " + jobState);
                            
                            String errorMsg = "Slurm job " + jobId + " failed with state: " + jobState + ". " +
                                "Agent will not connect. Check Slurm logs: slurm-" + jobId + ".out";
                            
                            // Set problem field to prevent retries
                            IOException exception = new IOException(errorMsg);
                            setProblem(exception);
                            
                            // Log error details to build console BEFORE cancelling queue
                            listener.getLogger().println("");
                            listener.getLogger().println("========================================");
                            listener.getLogger().println("SLURM AGENT PROVISIONING FAILED");
                            listener.getLogger().println("========================================");
                            listener.getLogger().println("Slurm Job ID: " + jobId);
                            listener.getLogger().println("Job State: " + jobState);
                            listener.getLogger().println("Reason: Slurm job failed during execution");
                            listener.getLogger().println("");
                            listener.getLogger().println("To investigate:");
                            listener.getLogger().println("  1. Check Slurm job logs: slurm-" + jobId + ".out");
                            listener.getLogger().println("  2. Check Slurm job error logs: slurm-" + jobId + ".err (if exists)");
                            listener.getLogger().println("  3. Run: scontrol show job " + jobId + " (if job still in history)");
                            listener.getLogger().println("");
                            listener.getLogger().println("Common causes:");
                            listener.getLogger().println("  - Bad container image specified in template");
                            listener.getLogger().println("  - Insufficient resources (memory/CPU)");
                            listener.getLogger().println("  - Node allocation failure");
                            listener.getLogger().println("  - Job time limit exceeded");
                            listener.getLogger().println("========================================");
                            listener.getLogger().println("");
                            
                            // Cancel the job immediately
                            try {
                                cloud.cancelJob(jobId, listener);
                                LOGGER.info("Canceled failed Slurm job: " + jobId);
                            } catch (Exception cancelEx) {
                                LOGGER.log(Level.WARNING, "Failed to cancel job " + jobId, cancelEx);
                            }
                            
                            // Kubernetes pattern: Cancel queue item to prevent infinite loop
                            JobUtils.cancelQueueItemFor(agent, "Slurm job failed with state: " + jobState);
                            
                            listener.error(errorMsg);
                            LOGGER.severe(errorMsg);
                            
                            // Mark computer offline before terminating
                            computer.setTemporarilyOffline(true, 
                                new OfflineCause.UserCause(null, errorMsg));
                            
                            // CRITICAL: Terminate node BEFORE throwing exception
                            // This removes the node from Jenkins so it can't be relaunched
                            // Kubernetes pattern: node must be gone before exception propagates
                            try {
                                agent.terminate();
                                LOGGER.info("Terminated failed agent node: " + agent.getNodeName());
                            } catch (IOException | InterruptedException removeEx) {
                                LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
                            }
                            
                            LOGGER.severe("FAIL-FAST: Throwing IOException to exit launch loop");
                            throw exception;
                        }
                        
                        // CRITICAL: Check if job completed without agent connection
                        // This happens when the Slurm job runs but the startup script fails
                        // (e.g., bad Jenkins URL, network issues, agent.jar download failure)
                        if (jobState.equals("COMPLETED") && !computer.isOnline()) {
                            LOGGER.severe("FAIL-FAST: Job " + jobId + " COMPLETED but agent never connected");
                            
                            String errorMsg = "Slurm job " + jobId + " completed but agent failed to connect. " +
                                "This usually means the startup script failed. Check Slurm logs: slurm-" + jobId + ".out";
                            
                            // Set problem field to prevent retries
                            IOException exception = new IOException(errorMsg);
                            setProblem(exception);
                            
                            // Log error details to build console BEFORE cancelling queue
                            listener.getLogger().println("");
                            listener.getLogger().println("========================================");
                            listener.getLogger().println("SLURM AGENT CONNECTION FAILED");
                            listener.getLogger().println("========================================");
                            listener.getLogger().println("Slurm Job ID: " + jobId);
                            listener.getLogger().println("Job State: COMPLETED");
                            listener.getLogger().println("Problem: Job completed but agent never connected to Jenkins");
                            listener.getLogger().println("");
                            listener.getLogger().println("To investigate:");
                            listener.getLogger().println("  1. Check Slurm job output: slurm-" + jobId + ".out");
                            listener.getLogger().println("  2. Look for agent startup errors in the output");
                            listener.getLogger().println("  3. Verify Jenkins URL is accessible from Slurm nodes");
                            listener.getLogger().println("");
                            listener.getLogger().println("Common causes:");
                            listener.getLogger().println("  - Incorrect Jenkins URL in cloud configuration");
                            listener.getLogger().println("  - Network connectivity issues from Slurm nodes to Jenkins");
                            listener.getLogger().println("  - Firewall blocking JNLP port (default: " + getJnlpPort() + ")");
                            listener.getLogger().println("  - Failed to download agent.jar");
                            listener.getLogger().println("  - Java not available on Slurm node");
                            listener.getLogger().println("========================================");
                            listener.getLogger().println("");
                            
                            // Job already completed, no need to cancel
                            
                            // Kubernetes pattern: Cancel queue item to prevent infinite loop
                            JobUtils.cancelQueueItemFor(agent, "Job completed but agent failed to connect");
                            
                            listener.error(errorMsg);
                            LOGGER.severe(errorMsg);
                            
                            // Mark computer offline before terminating
                            computer.setTemporarilyOffline(true, 
                                new OfflineCause.UserCause(null, errorMsg));
                            
                            // Terminate node before throwing
                            try {
                                agent.terminate();
                                LOGGER.info("Terminated failed agent node: " + agent.getNodeName());
                            } catch (IOException | InterruptedException removeEx) {
                                LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
                            }
                            
                            LOGGER.severe("FAIL-FAST: Throwing IOException to exit launch loop");
                            throw exception;
                        }
                    }
                    
                    lastStatusCheck = System.currentTimeMillis();
                    
                } catch (ApiException e) {
                    // Log but don't fail - continue waiting for agent
                    LOGGER.log(Level.FINE, "Failed to check job status for " + jobId, e);
                } catch (IOException ioe) {
                    // Re-throw IOException from failed job state
                    throw ioe;
                }
            }
            
            // Log progress periodically (unless job is running and we're waiting for connection)
            if (!jobRunning && System.currentTimeMillis() - lastProgressLog >= PROGRESS_LOG_INTERVAL_MS) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                listener.getLogger().println("Waiting for Slurm job to start running... (" + 
                    elapsed + " seconds elapsed)");
                lastProgressLog = System.currentTimeMillis();
            }
            
            // Check if agent is online
            // Kubernetes pattern: Both conditions must be met:
            // 1. Job is RUNNING (like pod ready)
            // 2. Computer is online (JNLP channel established)
            if (jobRunning && computer.isOnline()) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                listener.getLogger().println("Agent connected successfully after " + 
                    elapsed + " seconds (job RUNNING + JNLP connected)");
                LOGGER.info("Agent " + agent.getNodeName() + " connected successfully after " + 
                    elapsed + " seconds (job RUNNING + JNLP connected)");
                
                // Kubernetes pattern: enable tasks and mark as launched
                computer.setAcceptingTasks(true);
                launched = true;
                
                // Kubernetes pattern: persist the launched state
                try {
                    agent.save();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not save() agent: " + e.getMessage(), e);
                }
                
                return;
            }
            
            // Log if only one condition is met
            if (jobRunning && !computer.isOnline()) {
                if (System.currentTimeMillis() - lastProgressLog >= PROGRESS_LOG_INTERVAL_MS) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    listener.getLogger().println("Job is RUNNING, waiting for JNLP connection... (" + 
                        elapsed + " seconds elapsed)");
                    lastProgressLog = System.currentTimeMillis();
                }
            } else if (!jobRunning && computer.isOnline()) {
                // This shouldn't normally happen but log if it does
                LOGGER.warning("Agent connected but job not in RUNNING state yet");
            }
            
            // Sleep before next check
            Thread.sleep(1000);
        }
        
        // Timeout reached - cancel job and terminate node
        String timeoutReason = !jobRunning ? "job never reached RUNNING state" : 
                              !computer.isOnline() ? "JNLP connection never established" :
                              "unknown reason";
        String errorMsg = "Timeout waiting for agent to launch after " + 
            (AGENT_TIMEOUT_MS / 1000) + " seconds (" + timeoutReason + "). " +
            "Slurm job ID: " + jobId + ". Check Slurm job logs: slurm-" + jobId + ".out";
        
        // Set problem field to prevent retries
        IOException exception = new IOException(errorMsg);
        setProblem(exception);
        
        // Log error details to build console BEFORE cancelling queue
        listener.getLogger().println("");
        listener.getLogger().println("========================================");
        listener.getLogger().println("SLURM AGENT LAUNCH TIMEOUT");
        listener.getLogger().println("========================================");
        listener.getLogger().println("Slurm Job ID: " + jobId);
        listener.getLogger().println("Timeout: " + (AGENT_TIMEOUT_MS / 1000) + " seconds");
        listener.getLogger().println("Reason: " + timeoutReason);
        listener.getLogger().println("");
        if (!jobRunning) {
            listener.getLogger().println("The Slurm job never reached RUNNING state.");
            listener.getLogger().println("This usually means:");
            listener.getLogger().println("  - Job is queued waiting for resources");
            listener.getLogger().println("  - No nodes available matching job requirements");
            listener.getLogger().println("  - Job was held or blocked by Slurm policies");
            listener.getLogger().println("");
            listener.getLogger().println("Check:");
            listener.getLogger().println("  - Run: squeue -j " + jobId);
            listener.getLogger().println("  - Run: scontrol show job " + jobId);
        } else {
            listener.getLogger().println("The Slurm job started but agent failed to connect.");
            listener.getLogger().println("Check:");
            listener.getLogger().println("  - Slurm job output: slurm-" + jobId + ".out");
            listener.getLogger().println("  - Network connectivity from Slurm nodes to Jenkins");
            listener.getLogger().println("  - JNLP port accessibility (default: " + getJnlpPort() + ")");
        }
        listener.getLogger().println("========================================");
        listener.getLogger().println("");
        
        listener.error(errorMsg);
        LOGGER.severe(errorMsg);
        
        // Cancel the Slurm job
        try {
            cloud.cancelJob(jobId, listener);
            LOGGER.info("Canceled timed-out Slurm job: " + jobId);
        } catch (Exception cancelEx) {
            LOGGER.log(Level.WARNING, "Failed to cancel job " + jobId, cancelEx);
        }
        
        // Kubernetes pattern: Cancel queue item to prevent infinite loop
        JobUtils.cancelQueueItemFor(agent, "Agent connection timeout: " + timeoutReason);
        
        // Mark computer offline
        computer.setTemporarilyOffline(true, 
            new OfflineCause.UserCause(null, errorMsg));
        
        // Terminate node BEFORE throwing - prevents Jenkins from retrying launch on same computer
        try {
            agent.terminate();
            LOGGER.info("Terminated timed-out agent node: " + agent.getNodeName());
        } catch (IOException | InterruptedException removeEx) {
            LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
        }
        
        throw exception;
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
        LOGGER.warning("Please configure Jenkins URL in: Manage Jenkins > System > Jenkins Location OR in the Slurm Cloud configuration");
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
    
    /**
     * Gets the JNLP port for agent connections.
     * Returns "JNLP" if using WebSocket, or the actual port number.
     */
    private String getJnlpPort() {
        try {
            Jenkins jenkins = Jenkins.get();
            int port = jenkins.getTcpSlaveAgentListener() != null ? 
                      jenkins.getTcpSlaveAgentListener().getAdvertisedPort() : -1;
            if (port > 0) {
                return String.valueOf(port);
            }
            return "JNLP/WebSocket";
        } catch (Exception e) {
            return "JNLP";
        }
    }
    
    /**
     * The last problem that occurred, if any.
     * Following Kubernetes plugin pattern.
     * 
     * @return the last problem, or null if no problem
     */
    @CheckForNull
    public Throwable getProblem() {
        return problem;
    }
    
    /**
     * Sets the problem that occurred during launch.
     * Following Kubernetes plugin pattern - stores first failure to prevent retries.
     * 
     * @param problem the problem that occurred
     */
    public void setProblem(@CheckForNull Throwable problem) {
        this.problem = problem;
    }
    
    @Extension
    public static final class DescriptorImpl extends Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Launch Slurm agent";
        }
    }
}
