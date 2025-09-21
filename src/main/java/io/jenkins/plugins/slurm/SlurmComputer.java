package io.jenkins.plugins.slurm;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.logging.Logger;

/**
 * Computer implementation for SLURM agents.
 * 
 * This class manages the execution state and lifecycle of a SLURM-based
 * Jenkins agent, including handling connection status and job execution.
 */
public class SlurmComputer extends AbstractCloudComputer<SlurmAgent> {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmComputer.class.getName());
    
    public SlurmComputer(@NonNull SlurmAgent agent) {
        super(agent);
        LOGGER.info("Created SLURM computer for agent: " + agent.getNodeName());
    }
    
    /**
     * Gets the SLURM agent associated with this computer.
     */
    @Override
    @NonNull
    public SlurmAgent getNode() {
        return super.getNode();
    }
    
    /**
     * Determines if this computer can accept the given task.
     * 
     * This method is called by Jenkins to determine if this agent
     * can execute a particular build job.
     */
    @Override
    public boolean isAcceptingTasks() {
        // Only accept tasks if we're online and not temporarily offline
        return super.isAcceptingTasks() && isOnline() && !isTemporarilyOffline();
    }
    
    /**
     * Called when the computer goes offline.
     * We use this to clean up SLURM resources if needed.
     */
    public void onConnected() {
        SlurmAgent agent = getNode();
        if (agent != null) {
            LOGGER.info("SLURM agent connected: " + agent.getNodeName() + 
                       " (job=" + agent.getSlurmJobId() + ")");
        }
    }
    
    /**
     * Called when the computer goes offline.
     */
    public void onDisconnected() {
        SlurmAgent agent = getNode();
        if (agent != null) {
            LOGGER.info("SLURM agent disconnected: " + agent.getNodeName() + 
                       " (job=" + agent.getSlurmJobId() + ")");
        }
    }
    
    /**
     * Gets display information about the SLURM job for the UI.
     */
    public String getSlurmJobInfo() {
        SlurmAgent agent = getNode();
        if (agent == null) {
            return "N/A";
        }
        
        StringBuilder info = new StringBuilder();
        info.append("Job ID: ").append(agent.getSlurmJobId() != null ? agent.getSlurmJobId() : "Unknown");
        
        if (agent.getPartition() != null && !agent.getPartition().isEmpty()) {
            info.append(", Partition: ").append(agent.getPartition());
        }
        
        if (agent.getNodeList() != null && !agent.getNodeList().isEmpty()) {
            info.append(", Nodes: ").append(agent.getNodeList());
        }
        
        return info.toString();
    }
    
    /**
     * Determines if this computer should be retained or terminated.
     * 
     * This is called by the retention strategy to decide when to
     * terminate idle agents.
     */
    public boolean shouldBeRetained() {
        // Keep the agent if it's currently executing jobs
        for (Executor executor : getExecutors()) {
            if (executor.isBusy()) {
                return true;
            }
        }
        
        // Keep the agent if there are jobs in the queue that could use it
        Queue.Item[] items = Queue.getInstance().getItems();
        for (Queue.Item item : items) {
            if (item.task != null && getNode() != null) {
                if (getNode().canTake(item.task) == null) {
                    return true;
                }
            }
        }
        
        return false;
    }
}