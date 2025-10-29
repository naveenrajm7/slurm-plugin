package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Label;

import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Utility methods for working with SLURM job templates.
 * 
 * Provides helper methods for finding, filtering, and selecting templates.
 * Similar to Kubernetes plugin's PodTemplateUtils.
 */
public class SlurmJobTemplateUtils {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmJobTemplateUtils.class.getName());
    
    /**
     * Gets all available job templates for a cloud, including templates from all sources.
     * 
     * @param cloud The SLURM cloud instance
     * @return All available templates from all registered sources
     */
    @NonNull
    public static List<SlurmJobTemplate> getAllTemplates(@NonNull SlurmCloud cloud) {
        return SlurmJobTemplateSource.getAll(cloud);
    }
    
    /**
     * Gets filtered job templates for a specific label.
     * 
     * This method retrieves all templates and filters them through all registered filters,
     * considering the requested label.
     * 
     * @param cloud The SLURM cloud instance
     * @param label The label to match (can be null for unlabeled jobs)
     * @return Filtered list of templates suitable for the label
     */
    @NonNull
    public static List<SlurmJobTemplate> getTemplatesFor(@NonNull SlurmCloud cloud, @CheckForNull Label label) {
        List<SlurmJobTemplate> allTemplates = getAllTemplates(cloud);
        
        LOGGER.fine("Found " + allTemplates.size() + " total templates for cloud " + cloud.getDisplayName());
        
        // Apply all filters
        List<SlurmJobTemplate> filteredTemplates = SlurmJobTemplateFilter.applyAll(cloud, allTemplates, label);
        
        LOGGER.fine("After filtering: " + filteredTemplates.size() + " templates match label " + 
                   (label != null ? label.getName() : "none"));
        
        return filteredTemplates;
    }
    
    /**
     * Gets the best matching job template for the given label.
     * 
     * Returns the first template that matches the label, or null if no match is found.
     * 
     * @param cloud The SLURM cloud instance
     * @param label The label to match
     * @return The best matching template, or null if none found
     */
    @CheckForNull
    public static SlurmJobTemplate getTemplateByLabel(@NonNull SlurmCloud cloud, @CheckForNull Label label) {
        List<SlurmJobTemplate> templates = getTemplatesFor(cloud, label);
        
        if (templates.isEmpty()) {
            LOGGER.fine("No templates found for label: " + (label != null ? label.getName() : "none"));
            return null;
        }
        
        // Return first matching template
        SlurmJobTemplate selected = templates.get(0);
        LOGGER.fine("Selected template '" + selected.getName() + "' for label: " + 
                   (label != null ? label.getName() : "none"));
        
        return selected;
    }
    
    /**
     * Gets a job template by name from the cloud's configured templates.
     * 
     * @param cloud The SLURM cloud instance
     * @param name The template name to search for
     * @return The template with matching name, or null if not found
     */
    @CheckForNull
    public static SlurmJobTemplate getTemplateByName(@NonNull SlurmCloud cloud, @CheckForNull String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        
        List<SlurmJobTemplate> allTemplates = getAllTemplates(cloud);
        
        for (SlurmJobTemplate template : allTemplates) {
            if (name.equals(template.getName())) {
                LOGGER.fine("Found template by name: " + name);
                return template;
            }
        }
        
        LOGGER.fine("No template found with name: " + name);
        return null;
    }
    
    /**
     * Validates that a template name is unique within the cloud.
     * 
     * @param cloud The SLURM cloud instance
     * @param templateName The name to check
     * @param excludeTemplate Template to exclude from check (when renaming)
     * @return true if name is unique, false otherwise
     */
    public static boolean isTemplateNameUnique(@NonNull SlurmCloud cloud, 
                                              @NonNull String templateName,
                                              @CheckForNull SlurmJobTemplate excludeTemplate) {
        List<SlurmJobTemplate> allTemplates = getAllTemplates(cloud);
        
        for (SlurmJobTemplate template : allTemplates) {
            // Skip the template being renamed
            if (excludeTemplate != null && template.getId().equals(excludeTemplate.getId())) {
                continue;
            }
            
            if (templateName.equals(template.getName())) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Counts the number of templates with a specific label.
     * 
     * @param cloud The SLURM cloud instance
     * @param label The label to count
     * @return Number of templates matching the label
     */
    public static int countTemplatesForLabel(@NonNull SlurmCloud cloud, @CheckForNull Label label) {
        List<SlurmJobTemplate> templates = getTemplatesFor(cloud, label);
        return templates.size();
    }
    
    /**
     * Checks if the cloud has any templates configured.
     * 
     * @param cloud The SLURM cloud instance
     * @return true if at least one template exists
     */
    public static boolean hasTemplates(@NonNull SlurmCloud cloud) {
        List<SlurmJobTemplate> templates = getAllTemplates(cloud);
        return !templates.isEmpty();
    }
    
    /**
     * Creates a default template with basic settings.
     * Used as fallback when no templates are configured.
     * 
     * @param cloud The SLURM cloud instance to get defaults from
     * @return A default job template
     */
    @NonNull
    public static SlurmJobTemplate createDefaultTemplate(@NonNull SlurmCloud cloud) {
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setName("default");
        template.setLabel(""); // Matches any label
        template.setPartition(cloud.getDefaultPartition());
        template.setCurrentWorkingDirectory("/tmp/jenkins-" + cloud.getDisplayName());
        template.setCpusPerTask(1);
        template.setMemoryPerNode(2048L); // 2GB default
        template.setTimeLimit(60); // 1 hour default
        template.setInstanceCap(cloud.getMaxAgents());
        template.setIdleMinutes(5);
        
        LOGGER.info("Created default template for cloud: " + cloud.getDisplayName());
        
        return template;
    }
}
