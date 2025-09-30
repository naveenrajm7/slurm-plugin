package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Label;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters SLURM job templates according to criteria.
 * 
 * This extension point allows filtering of job templates based on various criteria
 * such as labels, permissions, or custom business logic.
 * 
 * Similar to Kubernetes plugin's PodTemplateFilter.
 */
public abstract class SlurmJobTemplateFilter implements ExtensionPoint {
    
    /**
     * Returns a list of all implementations of {@link SlurmJobTemplateFilter}.
     * 
     * @return List of all registered filter implementations
     */
    public static ExtensionList<SlurmJobTemplateFilter> all() {
        return ExtensionList.lookup(SlurmJobTemplateFilter.class);
    }
    
    /**
     * Pass the given job templates list through all filter implementations.
     * 
     * @param cloud The SLURM cloud instance the templates are being considered for
     * @param jobTemplates The initial list of job templates
     * @param label The label that was requested for provisioning
     * @return The filtered list of job templates
     */
    public static List<SlurmJobTemplate> applyAll(
            @NonNull SlurmCloud cloud, 
            @NonNull List<SlurmJobTemplate> jobTemplates, 
            @CheckForNull Label label) {
        
        List<SlurmJobTemplate> result = new ArrayList<>();
        
        for (SlurmJobTemplate template : jobTemplates) {
            SlurmJobTemplate filtered = template;
            
            // Apply each filter in sequence
            for (SlurmJobTemplateFilter filter : all()) {
                filtered = filter.transform(cloud, filtered, label);
                if (filtered == null) {
                    break; // Template was rejected by this filter
                }
            }
            
            // Add to result if not filtered out
            if (filtered != null) {
                result.add(filtered);
            }
        }
        
        return result;
    }
    
    /**
     * Transforms a job template definition.
     * 
     * @param cloud The {@link SlurmCloud} instance the template will be used with
     * @param jobTemplate The input job template to process
     * @param label The label that was requested for provisioning
     * @return A transformed job template, or null if the template should be filtered out
     */
    @CheckForNull
    protected abstract SlurmJobTemplate transform(
            @NonNull SlurmCloud cloud, 
            @NonNull SlurmJobTemplate jobTemplate, 
            @CheckForNull Label label);
}
