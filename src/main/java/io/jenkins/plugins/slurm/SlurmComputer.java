package io.jenkins.plugins.slurm;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Computer implementation for SLURM agents.
 * 
 * This class manages the execution state and lifecycle of a SLURM-based
 * Jenkins agent, including handling connection status and job execution.
 */
public class SlurmComputer extends AbstractCloudComputer<SlurmAgent> {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmComputer.class.getName());
    
    private volatile boolean launching = false;
    
    public SlurmComputer(@NonNull SlurmAgent agent) {
        super(agent);
        LOGGER.log(Level.FINE, "Created SLURM computer for agent: {0}", agent.getNodeName());
    }
    
    /**
     * Gets the SLURM agent associated with this computer.
     */
    @Override
    @CheckForNull
    public SlurmAgent getNode() {
        return super.getNode();
    }
    
    /**
     * Indicates if this computer is currently in the process of being launched.
     * 
     * @return true if SLURM job has been submitted and agent is waiting to connect
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
     * Gets display information about the SLURM job for the UI.
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
        }
    }
    
    @Override
    public String toString() {
        return String.format("SlurmComputer[%s]", getName());
    }
}