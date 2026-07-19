package io.jenkins.plugins.slurm;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Id;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a Jenkins agent running as a Slurm job.
 * 
 * This class extends AbstractCloudSlave to provide integration with
 * Jenkins' cloud agent lifecycle management and implements TrackedItem
 * for cloud-stats integration.
 */
public class SlurmAgent extends AbstractCloudSlave implements TrackedItem {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmAgent.class.getName());
    
    private static final long serialVersionUID = 1L;
    
    private final String cloudName;
    private String slurmJobId;  // Non-final because it might be set after creation
    private final String templateId;
    private final String partition;
    private String nodeList;  // Non-final because it's set when job starts
    
    // Cloud-stats tracking ID
    private final Id cloudStatsId;
    
    // Pipeline build context (transient - not persisted)
    private transient TaskListener runListener;  // Build's TaskListener for pipeline error reporting
    
    /**
     * Creates a new Slurm agent.
     * 
     * @param name Agent name (unique identifier)
     * @param description Human-readable description
     * @param remoteFS Remote file system root on the agent
     * @param numExecutors Number of concurrent executors
     * @param mode Agent usage mode (NORMAL or EXCLUSIVE)
     * @param labelString Space-separated labels
     * @param launcher Launcher that will connect this agent
     * @param retentionStrategy Strategy for keeping/terminating the agent
     * @param nodeProperties Additional node properties
     * @param cloudName Name of the Slurm cloud that created this agent
     * @param templateId ID of the job template used
     * @param partition Slurm partition for this agent
     * @param cloudStatsId Cloud-stats tracking ID (for lifecycle tracking)
     */
    public SlurmAgent(@NonNull String name,
                      @NonNull String description,
                      @NonNull String remoteFS,
                      int numExecutors,
                      @NonNull Mode mode,
                      @NonNull String labelString,
                      @NonNull ComputerLauncher launcher,
                      @NonNull RetentionStrategy<?> retentionStrategy,
                      @NonNull List<? extends NodeProperty<?>> nodeProperties,
                      @NonNull String cloudName,
                      @NonNull String templateId,
                      String partition,
                      @CheckForNull Id cloudStatsId) throws Descriptor.FormException, IOException {
        
        super(name, description, remoteFS, numExecutors, mode, labelString, 
              launcher, retentionStrategy, nodeProperties);
        
        this.cloudName = cloudName;
        this.templateId = templateId;
        this.partition = partition;
        this.slurmJobId = null;  // Will be set when job is submitted
        this.nodeList = null;     // Will be set when job starts
        
        // Store the cloud-stats ID for tracking, or create a new one if not provided
        this.cloudStatsId = cloudStatsId != null ? cloudStatsId : 
                           new Id(cloudName, templateId, name);
        
        LOGGER.log(Level.INFO, "Created Slurm agent: {0} (cloud={1}, template={2}, partition={3})", 
                  new Object[]{name, cloudName, templateId, partition});
    }
    
    /**
     * Gets the cloud-stats tracking ID.
     * This ID connects PlannedNode -> Node -> Computer for lifecycle tracking.
     */
    @NonNull
    @Override
    public Id getId() {
        return cloudStatsId;
    }
    
    /**
     * Gets the name of the cloud that created this agent.
     */
    @NonNull
    public String getCloudName() {
        return cloudName;
    }
    
    /**
     * Gets the template ID used to create this agent.
     */
    @NonNull
    public String getTemplateId() {
        return templateId;
    }
    
    /**
     * Gets the Slurm job ID for this agent.
     * May be null if job hasn't been submitted yet.
     */
    @CheckForNull
    public String getSlurmJobId() {
        return slurmJobId;
    }
    
    /**
     * Sets the Slurm job ID after job submission and exposes it as a build environment variable.
     */
    public void setSlurmJobId(@NonNull String slurmJobId) {
        this.slurmJobId = slurmJobId;
        updateSlurmEnvProp();
        LOGGER.log(Level.FINE, "Set Slurm job ID for agent {0}: {1}",
                  new Object[]{getNodeName(), slurmJobId});
    }
    
    /**
     * Gets the Slurm partition this agent is running in.
     */
    @CheckForNull
    public String getPartition() {
        return partition;
    }
    
    /**
     * Gets the Slurm node list assigned to this job.
     * May be null if job hasn't started yet.
     */
    @CheckForNull
    public String getNodeList() {
        return nodeList;
    }
    
    /**
     * Sets the Slurm node list after job starts and exposes it as a build environment variable.
     */
    public void setNodeList(@NonNull String nodeList) {
        this.nodeList = nodeList;
        updateSlurmEnvProp();
        LOGGER.log(Level.FINE, "Set node list for agent {0}: {1}",
                  new Object[]{getNodeName(), nodeList});
    }

    /**
     * Keeps the {@link SlurmEnvironmentNodeProperty} on this agent in sync with the latest
     * Slurm job placement info ({@code SLURM_JOB_ID}, {@code SLURM_NODELIST}).
     *
     * <p>Called whenever {@code slurmJobId} or {@code nodeList} is updated so that pipeline
     * steps can access these values via {@code env.SLURM_JOB_ID} / {@code env.SLURM_NODELIST}
     * in Groovy, or {@code $SLURM_JOB_ID} / {@code $SLURM_NODELIST} in shell steps.
     */
    void updateSlurmEnvProp() {
        SlurmEnvironmentNodeProperty prop = null;
        for (NodeProperty<?> p : getNodeProperties()) {
            if (p instanceof SlurmEnvironmentNodeProperty) {
                prop = (SlurmEnvironmentNodeProperty) p;
                break;
            }
        }
        if (prop == null) {
            prop = new SlurmEnvironmentNodeProperty();
            try {
                getNodeProperties().add(prop);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Failed to add SlurmEnvironmentNodeProperty to agent " + getNodeName(), e);
                return;
            }
        }
        prop.setSlurmJobId(slurmJobId);
        prop.setSlurmNodeList(nodeList);
    }

    /**
     * Records compute placement from a Slurm job status poll and updates the agent description.
     */
    public void applyJobPlacement(@CheckForNull io.jenkins.plugins.slurm.client.SlurmJobStatus status) {
        if (status != null && status.getNodes() != null && !status.getNodes().isBlank()) {
            setNodeList(status.getNodes());
        }
        refreshNodeDescription();
    }

    /**
     * Updates the Jenkins agent description with partition and compute node(s) once known.
     */
    public void refreshNodeDescription() {
        StringBuilder sb = new StringBuilder();
        try {
            SlurmJobTemplate template = getSlurmCloud().getTemplateById(templateId);
            if (template != null) {
                sb.append("Slurm agent from template ").append(template.getName());
            } else {
                sb.append("Slurm agent");
            }
        } catch (IllegalStateException e) {
            sb.append("Slurm agent");
        }

        if (partition != null && !partition.isBlank()) {
            sb.append(", partition ").append(partition);
        }
        if (nodeList != null && !nodeList.isBlank()) {
            sb.append(", on ").append(nodeList);
        }
        if (slurmJobId != null) {
            sb.append(" (job ").append(slurmJobId).append(')');
        }

        setNodeDescription(sb.toString());
    }
    
    /**
     * Gets the Slurm cloud instance that created this agent.
     * 
     * @return Slurm cloud instance
     * @throws IllegalStateException if cloud no longer exists
     */
    @NonNull
    public SlurmCloud getSlurmCloud() throws IllegalStateException {
        Cloud cloud = Jenkins.get().getCloud(cloudName);
        
        if (cloud == null) {
            throw new IllegalStateException("Cloud '" + cloudName + "' no longer exists");
        }
        
        if (!(cloud instanceof SlurmCloud)) {
            throw new IllegalStateException("Cloud '" + cloudName + "' is not a SlurmCloud: " + 
                                           cloud.getClass().getName());
        }
        
        return (SlurmCloud) cloud;
    }
    
    /**
     * Makes a best effort to find the build log corresponding to this agent.
     * This allows error messages from the launcher to appear in the pipeline build console.
     * 
     * Following K8s pattern: First checks cached listener (set during provisioning),
     * then falls back to scanning executors.
     * 
     * @return TaskListener for the build that is using this agent, or TaskListener.NULL if not found
     */
    @NonNull
    public TaskListener getRunListener() {
        // FIRST: Check if we have a cached listener from provisioning (K8s pattern)
        if (runListener != null) {
            return runListener;
        }
        
        // SECOND: Fall back to scanning active executors
        Computer c = toComputer();
        if (c != null) {
            for (Executor executor : c.getExecutors()) {
                Queue.Executable executable = executor.getCurrentExecutable();
                // If this executor hosts a PlaceholderExecutable, send to the owning build log.
                if (executable != null) {
                    Queue.Executable parentExecutable = executable.getParentExecutable();
                    if (parentExecutable instanceof FlowExecutionOwner.Executable) {
                        FlowExecutionOwner flowExecutionOwner =
                                ((FlowExecutionOwner.Executable) parentExecutable).asFlowExecutionOwner();
                        if (flowExecutionOwner != null) {
                            try {
                                return flowExecutionOwner.getListener();
                            } catch (IOException x) {
                                LOGGER.log(Level.WARNING, null, x);
                            }
                        }
                    }
                }
                // TODO handle freestyle and similar if executable instanceof Run, by capturing a TaskListener from
                // RunListener.onStarted
            }
        }
        return TaskListener.NULL;
    }
    
    /**
     * Sets the build's TaskListener for this agent.
     * This should be called during provisioning to enable error messages
     * to appear in the build console even before an executor is assigned.
     * 
     * @param runListener The TaskListener from the pipeline build
     */
    public void setRunListener(TaskListener runListener) {
        this.runListener = runListener;
    }
    
    /**
     * Creates the computer instance for this agent.
     */
    @Override
    @NonNull
    public SlurmComputer createComputer() {
        return new SlurmComputer(this);
    }
    
    /**
     * Terminates this Slurm agent.
     * This method is called by Jenkins when the agent should be shut down.
     */
    @Override
    protected void _terminate(@NonNull TaskListener listener) throws IOException, InterruptedException {
        LOGGER.log(Level.INFO, "Terminating Slurm agent: {0} (job={1})",
                  new Object[]{getNodeName(), slurmJobId});

        listener.getLogger().println("Terminating Slurm agent: " + getNodeName());

        // Mark the launcher as permanently failed so that Jenkins does not resubmit a
        // new Slurm job when JNLP disconnects after the job is cancelled.  Without this,
        // OnceRetentionStrategy.done() → _terminate() → Slurm job cancelled → JNLP
        // disconnects → Jenkins calls launch() again → a second Slurm job is submitted.
        hudson.model.Computer computer = toComputer();
        if (computer instanceof hudson.slaves.SlaveComputer) {
            hudson.slaves.ComputerLauncher rawLauncher = ((hudson.slaves.SlaveComputer) computer).getLauncher();
            if (rawLauncher instanceof SlurmLauncher) {
                ((SlurmLauncher) rawLauncher).setProblem(
                        new java.io.IOException("Agent terminated by retention strategy"));
                LOGGER.log(Level.FINE, "Marked launcher as terminated for agent: {0}", getNodeName());
            }
        }

        if (slurmJobId == null) {
            listener.getLogger().println("No Slurm job ID - agent may not have been started");
            return;
        }
        
        try {
            SlurmCloud cloud = getSlurmCloud();
            SlurmJobTemplate template = cloud.getTemplateById(templateId);
            
            // Check if we should cancel the job based on retention policy
            boolean shouldCancel = true;
            
            if (template != null && template.isKeepJobOnFailure()) {
                // User wants to keep jobs for debugging - just log and skip cancellation
                shouldCancel = false;
                listener.getLogger().println("Keeping Slurm job running for debugging (job ID: " + slurmJobId + ")");
                listener.getLogger().println("You can manually cancel it with: scancel " + slurmJobId);
                LOGGER.info("Keeping Slurm job " + slurmJobId + " running due to keepJobOnFailure=true");
            }
            
            if (shouldCancel) {
                // Cancel the Slurm job
                listener.getLogger().println("Cancelling Slurm job: " + slurmJobId);
                cloud.cancelJob(slurmJobId, listener);
                listener.getLogger().println("Slurm job cancelled successfully");
            }
            
        } catch (IllegalStateException e) {
            // Cloud no longer exists - log but don't fail
            LOGGER.log(Level.WARNING, "Cloud no longer exists, cannot cancel job: " + e.getMessage());
            listener.getLogger().println("Warning: Cloud no longer exists, Slurm job may still be running");
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to cancel Slurm job " + slurmJobId, e);
            listener.getLogger().println("Warning: Failed to cancel Slurm job: " + e.getMessage());
            // Don't throw - we want termination to succeed even if job cancellation fails
        }
    }
    
    @Override
    public String toString() {
        return String.format("SlurmAgent[name=%s, job=%s, cloud=%s]", 
                           getNodeName(), slurmJobId, cloudName);
    }
    
    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {
        @Override
        @NonNull
        public String getDisplayName() {
            return "Slurm Agent";
        }
        
        @Override
        public boolean isInstantiable() {
            return false;  // Don't show in UI - only created programmatically
        }
    }
}
