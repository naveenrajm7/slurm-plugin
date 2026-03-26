package io.jenkins.plugins.slurm;

import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

/**
 * Computer implementation for Slurm agents.
 * 
 * This class manages the execution state and lifecycle of a Slurm-based
 * Jenkins agent, including handling connection status and job execution.
 * Implements TrackedItem for cloud-stats plugin integration.
 */
public class SlurmComputer extends AbstractCloudComputer<SlurmAgent> implements TrackedItem {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmComputer.class.getName());
    
    private volatile boolean launching = false;
    
    public SlurmComputer(@NonNull SlurmAgent agent) {
        super(agent);
        LOGGER.log(Level.FINE, "Created Slurm computer for agent: {0}", agent.getNodeName());
    }
    
    /**
     * Gets the Slurm agent associated with this computer.
     */
    @Override
    @CheckForNull
    public SlurmAgent getNode() {
        return super.getNode();
    }
    
    /**
     * Indicates if this computer is currently in the process of being launched.
     * 
     * @return true if Slurm job has been submitted and agent is waiting to connect
     */
    public boolean isLaunching() {
        return launching;
    }
    
    /**
     * Sets the launching state.
     * Should be called by the launcher when job submission starts/completes.
     */
    public void setLaunching(boolean launching) {
        this.launching = launching;
        LOGGER.log(Level.FINE, "Agent {0} launching state: {1}", 
                  new Object[]{getName(), launching});
    }
    
    /**
     * Called when a task is accepted by this computer.
     */
    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        LOGGER.log(Level.FINE, "Computer {0} accepted task {1}",
                  new Object[]{this, task.getFullDisplayName()});
    }
    
    /**
     * Called when a task completes on this computer.
     */
    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        LOGGER.log(Level.FINE, "Computer {0} completed task {1} in {2}ms", 
                  new Object[]{this, task.getFullDisplayName(), durationMS});
        super.taskCompleted(executor, task, durationMS);
    }
    
    /**
     * Called when a task completes with problems.
     */
    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, 
                                         long durationMS, Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        LOGGER.log(Level.WARNING, "Computer {0} completed task {1} with problems", 
                  new Object[]{this, task.getFullDisplayName()});
    }
    
    /**
     * Gets display information about the Slurm job for the UI.
     */
    @NonNull
    public String getSlurmJobInfo() {
        SlurmAgent agent = getNode();
        if (agent == null) {
            return "N/A";
        }
        
        StringBuilder info = new StringBuilder();
        
        String jobId = agent.getSlurmJobId();
        info.append("Job ID: ").append(jobId != null ? jobId : "Not submitted");
        
        String partition = agent.getPartition();
        if (partition != null && !partition.isEmpty()) {
            info.append(", Partition: ").append(partition);
        }
        
        String nodeList = agent.getNodeList();
        if (nodeList != null && !nodeList.isEmpty()) {
            info.append(", Nodes: ").append(nodeList);
        }
        
        return info.toString();
    }
    
    @Override
    public void setAcceptingTasks(boolean acceptingTasks) {
        super.setAcceptingTasks(acceptingTasks);
        if (acceptingTasks) {
            setLaunching(false);
            // Agent has connected - remove from in-provisioning tracking
            SlurmAgent agent = getNode();
            if (agent != null) {
                // Get the label from the agent to remove from correct tracking set
                hudson.model.Label label = null;
                String labelString = agent.getLabelString();
                if (labelString != null && !labelString.isEmpty()) {
                    label = jenkins.model.Jenkins.get().getLabel(labelString);
                }
                LOGGER.fine("Agent " + agent.getNodeName() + " connected");
            }
        }
    }
    
    @Override
    public String toString() {
        return String.format("SlurmComputer[%s]", getName());
    }
    
    /**
     * Gets the cloud-stats tracking ID for this computer.
     * Returns the ID from the associated Slurm agent.
     * 
     * @return the cloud-stats tracking ID, or null if agent not available
     */
    @Override
    @CheckForNull
    public ProvisioningActivity.Id getId() {
        SlurmAgent agent = getNode();
        return agent != null ? agent.getId() : null;
    }
}