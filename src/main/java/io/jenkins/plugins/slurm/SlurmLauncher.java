package io.jenkins.plugins.slurm;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DelegatingComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Launcher for SLURM-based Jenkins agents.
 * 
 * This class handles the process of submitting a job to SLURM,
 * waiting for it to start, and then establishing an SSH connection
 * to the allocated compute node(s).
 */
public class SlurmLauncher extends DelegatingComputerLauncher {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmLauncher.class.getName());
    
    private final SlurmCloud cloud;
    private final String credentialsId;
    private final String partition;
    private final String slurmScriptTemplate;
    private final int timeoutMinutes;
    
    // Transient fields for job tracking
    private transient String slurmJobId;
    private transient String allocatedHost;
    
    public SlurmLauncher(@NonNull SlurmCloud cloud,
                         @NonNull String credentialsId,
                         String partition,
                         String slurmScriptTemplate,
                         int timeoutMinutes) {
        // Initialize with a placeholder launcher - will be replaced once we know the target host
        super(new PlaceholderLauncher());
        
        this.cloud = cloud;
        this.credentialsId = credentialsId;
        this.partition = partition != null ? partition : cloud.getDefaultPartition();
        this.slurmScriptTemplate = slurmScriptTemplate;
        this.timeoutMinutes = timeoutMinutes > 0 ? timeoutMinutes : cloud.getAgentTimeoutMinutes();
    }
    
    @Override
    public void launch(@NonNull SlaveComputer computer, @NonNull TaskListener listener) 
            throws IOException, InterruptedException {
        
        if (!(computer instanceof SlurmComputer)) {
            throw new IllegalArgumentException("Expected SlurmComputer, got " + computer.getClass());
        }
        
        SlurmComputer slurmComputer = (SlurmComputer) computer;
        SlurmAgent agent = slurmComputer.getNode();
        
        if (agent == null) {
            throw new IOException("No agent associated with computer");
        }
        
        listener.getLogger().println("Starting SLURM agent: " + agent.getNodeName());
        
        try {
            // Step 1: Submit job to SLURM
            slurmJobId = submitSlurmJob(agent, listener);
            if (slurmJobId == null) {
                throw new IOException("Failed to submit SLURM job");
            }
            
            listener.getLogger().println("Submitted SLURM job: " + slurmJobId);
            
            // Step 2: Wait for job to start and get allocated host
            allocatedHost = waitForJobToStart(slurmJobId, listener);
            if (allocatedHost == null) {
                throw new IOException("Failed to get allocated host for job " + slurmJobId);
            }
            
            listener.getLogger().println("Job allocated to host: " + allocatedHost);
            
            // Step 3: Create SSH launcher for the allocated host
            ComputerLauncher sshLauncher = createSSHLauncher(allocatedHost, agent);
            // Replace the delegate launcher
            launcher = sshLauncher;
            
            // Step 4: Launch using SSH
            super.launch(computer, listener);
            
        } catch (Exception e) {
            listener.error("Failed to launch SLURM agent: " + e.getMessage());
            // Cleanup job if it was submitted
            if (slurmJobId != null) {
                cancelSlurmJob(slurmJobId, listener);
            }
            throw new IOException("SLURM launch failed", e);
        }
    }
    
    /**
     * Submits a job to SLURM and returns the job ID.
     */
    private String submitSlurmJob(@NonNull SlurmAgent agent, @NonNull TaskListener listener) 
            throws IOException, InterruptedException {
        
        listener.getLogger().println("Submitting SLURM job for agent: " + agent.getNodeName());
        
        // TODO: Implement actual SLURM job submission
        // This would involve:
        // 1. Creating a SLURM batch script
        // 2. Executing sbatch command on the controller
        // 3. Parsing the job ID from the output
        
        // For now, return a mock job ID
        String mockJobId = "12345"; // In real implementation, this would come from sbatch output
        
        listener.getLogger().println("Mock SLURM job submitted with ID: " + mockJobId);
        return mockJobId;
    }
    
    /**
     * Waits for the SLURM job to start and returns the allocated hostname.
     */
    private String waitForJobToStart(@NonNull String jobId, @NonNull TaskListener listener) 
            throws IOException, InterruptedException {
        
        listener.getLogger().println("Waiting for SLURM job " + jobId + " to start...");
        
        long startTime = System.currentTimeMillis();
        long timeoutMs = TimeUnit.MINUTES.toMillis(timeoutMinutes);
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // TODO: Implement actual job status checking
            // This would involve executing: squeue -j <jobId> -h -o %N
            
            // For now, simulate job starting after 10 seconds
            if (System.currentTimeMillis() - startTime > 10000) {
                String mockHost = cloud.getSlurmControllerHost(); // In real implementation, would be compute node
                listener.getLogger().println("Mock job started on host: " + mockHost);
                return mockHost;
            }
            
            Thread.sleep(5000); // Check every 5 seconds
        }
        
        throw new IOException("Timeout waiting for SLURM job " + jobId + " to start");
    }
    
    /**
     * Creates an SSH launcher for connecting to the allocated host.
     */
    private ComputerLauncher createSSHLauncher(@NonNull String host, @NonNull SlurmAgent agent) {
        // Create SSH launcher using the allocated host
        return new SSHLauncher(
            host,                                    // host
            cloud.getSlurmControllerPort(),         // port (could be different for compute nodes)
            credentialsId,                          // credentials
            null,                                   // jvmOptions
            null,                                   // javaPath
            null,                                   // prefixStartSlaveCmd
            null,                                   // suffixStartSlaveCmd
            60,                                     // launchTimeoutSeconds
            3,                                      // maxNumRetries
            5,                                      // retryWaitTime
            null                                    // sshHostKeyVerificationStrategy
        );
    }
    
    /**
     * Cancels a SLURM job.
     */
    private void cancelSlurmJob(@NonNull String jobId, @NonNull TaskListener listener) {
        try {
            listener.getLogger().println("Cancelling SLURM job: " + jobId);
            // TODO: Implement actual job cancellation
            // This would execute: scancel <jobId>
        } catch (Exception e) {
            listener.getLogger().println("Warning: Failed to cancel SLURM job " + jobId + ": " + e.getMessage());
        }
    }
    
    public String getCredentialsId() {
        return credentialsId;
    }
    
    public String getPartition() {
        return partition;
    }
    
    public String getSlurmScriptTemplate() {
        return slurmScriptTemplate;
    }
    
    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }
    
    /**
     * Placeholder launcher used during initialization.
     */
    private static class PlaceholderLauncher extends ComputerLauncher {
        @Override
        public void launch(@NonNull SlaveComputer computer, @NonNull TaskListener listener) 
                throws IOException, InterruptedException {
            throw new IOException("Placeholder launcher should not be called directly");
        }
    }
}