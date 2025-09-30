package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A source of SLURM job templates.
 * 
 * This extension point allows plugins to contribute SLURM job templates from various sources,
 * such as cloud configuration, Jenkins configuration as code (JCasC), or external systems.
 * 
 * Similar to Kubernetes plugin's PodTemplateSource.
 */
public abstract class SlurmJobTemplateSource implements ExtensionPoint {
    
    /**
     * Returns all SLURM job templates from all registered sources for the given cloud.
     * 
     * @param cloud The SLURM cloud instance to get templates for
     * @return Combined list of all templates from all sources
     */
    public static List<SlurmJobTemplate> getAll(@NonNull SlurmCloud cloud) {
        return ExtensionList.lookup(SlurmJobTemplateSource.class).stream()
                .map(source -> source.getList(cloud))
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }
    
    /**
     * The list of {@link SlurmJobTemplate} contributed by this implementation.
     * 
     * @param cloud The SLURM cloud instance
     * @return The list of job templates provided by this source
     */
    @NonNull
    protected abstract List<SlurmJobTemplate> getList(@NonNull SlurmCloud cloud);
    
    /**
     * Default implementation that provides templates from the cloud's configuration.
     */
    @Extension
    public static class CloudConfigurationSource extends SlurmJobTemplateSource {
        
        @Override
        @NonNull
        protected List<SlurmJobTemplate> getList(@NonNull SlurmCloud cloud) {
            List<SlurmJobTemplate> templates = cloud.getJobTemplates();
            return templates != null ? new ArrayList<>(templates) : new ArrayList<>();
        }
    }
}
