package io.jenkins.plugins.slurm;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Represents a Jenkins agent running as a SLURM job.
 * 
 * This class extends AbstractCloudSlave to provide integration with
 * Jenkins' cloud agent lifecycle management.
 */
public class SlurmAgent extends AbstractCloudSlave {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmAgent.class.getName());
    
    private final String cloudName;
    private final String slurmJobId;
    private final String partition;
    private final String nodeList;
    
    public SlurmAgent(@NonNull String name,
                      @NonNull String description,
                      @NonNull String remoteFS,
                      int numExecutors,
                      @NonNull Mode mode,
                      @NonNull String labelString,
                      @NonNull ComputerLauncher launcher,
                      @NonNull RetentionStrategy retentionStrategy,
                      @NonNull List<? extends NodeProperty<?>> nodeProperties,
                      @NonNull String cloudName,
                      String slurmJobId,
                      String partition,
                      String nodeList) throws Descriptor.FormException, IOException {
        
        super(name, description, remoteFS, numExecutors, mode, labelString, 
              launcher, retentionStrategy, nodeProperties);
        
        this.cloudName = cloudName;
        this.slurmJobId = slurmJobId;
        this.partition = partition;
        this.nodeList = nodeList;
        
        LOGGER.info("Created SLURM agent: " + name + 
                   " (job=" + slurmJobId + ", partition=" + partition + ")");
    }
    
    /**
     * Gets the name of the cloud that created this agent.
     */
    public String getCloudName() {
        return cloudName;
    }
    
    /**
     * Gets the SLURM job ID for this agent.
     */
    public String getSlurmJobId() {
        return slurmJobId;
    }
    
    /**
     * Gets the SLURM partition this agent is running in.
     */
    public String getPartition() {
        return partition;
    }
    
    /**
     * Gets the SLURM node list assigned to this job.
     */
    public String getNodeList() {
        return nodeList;
    }
    
    public AbstractCloudSlave createNode(@NonNull String nodeName,
                                       @NonNull TaskListener listener) throws IOException, InterruptedException {
        // This method is called when the agent needs to be recreated
        // For SLURM agents, we would need to submit a new job
        LOGGER.info("Recreating SLURM agent: " + nodeName);
        
        // TODO: Implement node recreation logic
        // This would involve submitting a new SLURM job and creating a new SlurmAgent
        return this;
    }
    
    protected void _terminate(@NonNull TaskListener listener) throws IOException, InterruptedException {
        LOGGER.info("Terminating SLURM agent: " + getNodeName() + " (job=" + slurmJobId + ")");
        
        // TODO: Implement SLURM job cancellation
        // This should cancel the SLURM job using scancel command
        try {
            if (slurmJobId != null && !slurmJobId.isEmpty()) {
                // Would execute: scancel <slurmJobId>
                listener.getLogger().println("Cancelling SLURM job: " + slurmJobId);
                // Implementation needed
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to cancel SLURM job " + slurmJobId + ": " + e.getMessage());
            listener.error("Failed to cancel SLURM job: " + e.getMessage());
        }
    }
    
    /**
     * Creates a computer for this SLURM agent.
     */
    @Override
    @NonNull
    public SlurmComputer createComputer() {
        return new SlurmComputer(this);
    }
}