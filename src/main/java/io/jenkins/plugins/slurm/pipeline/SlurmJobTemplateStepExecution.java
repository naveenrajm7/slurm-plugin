package io.jenkins.plugins.slurm.pipeline;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.slurm.SlurmCloud;
import io.jenkins.plugins.slurm.SlurmFolderProperty;
import io.jenkins.plugins.slurm.SlurmJobTemplate;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Execution for {@link SlurmJobTemplateStep}.
 * 
 * Creates a temporary Slurm job template from the step configuration
 * and makes it available to nested pipeline steps.
 */
public class SlurmJobTemplateStepExecution extends StepExecution implements Serializable {
    
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(SlurmJobTemplateStepExecution.class.getName());
    
    private final SlurmJobTemplateStep step;
    
    public SlurmJobTemplateStepExecution(@NonNull SlurmJobTemplateStep step, @NonNull StepContext context) {
        super(context);
        this.step = step;
    }
    
    @Override
    public boolean start() throws Exception {
        // Resolve the Slurm cloud
        SlurmCloud cloud = resolveSlurmCloud();
        if (cloud == null) {
            getContext().onFailure(new AbortException(
                "No Slurm cloud found. Either specify a cloud name or configure a default Slurm cloud."
            ));
            return false;
        }
        
        LOGGER.log(Level.INFO, "Using Slurm cloud: {0}", cloud.name);
        
        // Check access if cloud is usage restricted
        Run<?, ?> run = getContext().get(Run.class);
        if (cloud.isUsageRestricted()) {
            checkAccess(run, cloud);
        }
        
        // Get the build's TaskListener first so JSON parse errors appear in the build console
        TaskListener stepListener = getContext().get(TaskListener.class);

        // Build the job template from step configuration.
        // Catch IllegalArgumentException here (e.g. unknown JSON field, missing "job" key)
        // and route the message to the build console instead of the server log.
        SlurmJobTemplate template;
        try {
            template = step.buildJobTemplate(cloud);
        } catch (IllegalArgumentException e) {
            stepListener.error("Invalid Slurm job template configuration: " + e.getMessage());
            getContext().onFailure(new AbortException(e.getMessage()));
            return false;
        }

        template.setListener(stepListener);
        
        // Temporarily add template to cloud if it has a label
        // This allows the cloud to provision agents based on this template
        boolean addedTemplate = false;
        if (!StringUtils.isEmpty(template.getLabel())) {
            LOGGER.log(Level.INFO, "Adding temporary template to cloud ''{0}'' with label: ''{1}''", 
                      new Object[]{cloud.name, template.getLabel()});
            LOGGER.log(Level.INFO, "Template details: name={0}, partition={1}, cpus={2}, memory={3}", 
                      new Object[]{template.getName(), template.getPartition(), 
                                  template.getCpusPerTask(), template.getMemoryPerNode()});
            cloud.addDynamicTemplate(template);
            addedTemplate = true;
            LOGGER.log(Level.INFO, "Template added successfully. Total templates in cloud: {0}", 
                      cloud.getJobTemplates().size());
        } else {
            LOGGER.log(Level.WARNING, "Template has no label, cannot be added to cloud for provisioning");
        }
        
        try {
            // Execute the body with the template in scope
            BodyInvoker bodyInvoker = getContext().newBodyInvoker()
                    .withCallback(new TemplateCleanupCallback(cloud, template, addedTemplate));
            
            bodyInvoker.start();
            
        } catch (Exception e) {
            // Clean up on error
            if (addedTemplate) {
                cloud.removeDynamicTemplate(template);
            }
            throw e;
        }
        
        return false;  // Async execution
    }
    
    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        getContext().onFailure(cause);
    }
    
    /**
     * Resolve which Slurm cloud to use for this template.
     */
    private SlurmCloud resolveSlurmCloud() throws AbortException {
        Jenkins jenkins = Jenkins.get();
        
        // If cloud name specified, use it
        if (!StringUtils.isEmpty(step.getCloud())) {
            for (SlurmCloud cloud : jenkins.clouds.getAll(SlurmCloud.class)) {
                if (step.getCloud().equals(cloud.name)) {
                    return cloud;
                }
            }
            throw new AbortException("Slurm cloud not found: " + step.getCloud());
        }
        
        // Otherwise use the first available Slurm cloud
        for (SlurmCloud cloud : jenkins.clouds.getAll(SlurmCloud.class)) {
            return cloud;
        }
        
        return null;
    }
    
    /**
     * Check if the current Job is permitted to use the cloud.
     *
     * @param run The current build run
     * @param slurmCloud The Slurm cloud to check access for
     * @throws AbortException if the Job has not been authorized to use the slurmCloud
     */
    private void checkAccess(Run<?, ?> run, SlurmCloud slurmCloud) throws AbortException {
        Job<?, ?> job = run.getParent(); // Return the associated Job for this Build
        ItemGroup<?> parent = job.getParent(); // Get the Parent of the Job (which might be a Folder)

        Set<String> allowedClouds = new HashSet<>();
        SlurmFolderProperty.collectAllowedClouds(allowedClouds, parent);
        if (!allowedClouds.contains(slurmCloud.name)) {
            throw new AbortException(String.format("Not authorized to use Slurm cloud: %s", step.getCloud()));
        }
    }
    
    /**
     * Callback to clean up the temporary template after execution.
     */
    private static class TemplateCleanupCallback extends BodyExecutionCallback {

        private static final long serialVersionUID = 1L;

        private final String cloudName;
        private final String templateId;   // UUID — unique per template instance, avoids label collisions
        private final String templateLabel; // for logging only
        private final boolean wasAdded;

        TemplateCleanupCallback(SlurmCloud cloud, SlurmJobTemplate template, boolean wasAdded) {
            this.cloudName = cloud.name;
            this.templateId = template.getId();
            this.templateLabel = template.getLabel();
            this.wasAdded = wasAdded;
        }

        @Override
        public void onSuccess(StepContext context, Object result) {
            cleanup();
            context.onSuccess(result);
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            cleanup();
            context.onFailure(t);
        }

        private void cleanup() {
            if (wasAdded && templateId != null && cloudName != null) {
                LOGGER.log(Level.FINE, "Removing temporary template id={0} (label={1}) from cloud: {2}",
                          new Object[]{templateId, templateLabel, cloudName});

                Jenkins jenkins = Jenkins.get();
                for (SlurmCloud cloud : jenkins.clouds.getAll(SlurmCloud.class)) {
                    if (cloudName.equals(cloud.name)) {
                        // Remove by ID — not by label — so concurrent runs with the same
                        // label don't remove each other's templates.
                        boolean removed = cloud.removeDynamicTemplateById(templateId);
                        if (removed) {
                            LOGGER.log(Level.INFO, "Removed temporary template id={0} (label={1}) from cloud: {2}",
                                      new Object[]{templateId, templateLabel, cloudName});
                        } else {
                            LOGGER.log(Level.WARNING, "Could not find template id={0} (label={1}) in cloud: {2} for cleanup",
                                      new Object[]{templateId, templateLabel, cloudName});
                        }
                        break;
                    }
                }
            }
        }
    }
}
