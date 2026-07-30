package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.Node;

/**
 * Implementation of {@link SlurmJobTemplateFilter} that filters job templates
 * based on label matching.
 *
 * This filter ensures that only templates with matching labels are considered
 * for provisioning. Similar to Kubernetes plugin's PodTemplateLabelFilter.
 */
@Extension(ordinal = 1000) // High ordinal = runs first
public class SlurmJobTemplateLabelFilter extends SlurmJobTemplateFilter {

    @Override
    @CheckForNull
    protected SlurmJobTemplate transform(
            @NonNull SlurmCloud cloud, @NonNull SlurmJobTemplate jobTemplate, @CheckForNull Label label) {

        // If no label requested and template is set to NORMAL mode (accept any job)
        if (label == null && jobTemplate.getNodeUsageMode() == Node.Mode.NORMAL) {
            return jobTemplate;
        }

        // If label requested and template can satisfy the expression (model after K8s plugin)
        if (label != null && jobTemplate.canTake(label)) {
            return jobTemplate;
        }

        // Template doesn't match - filter it out
        return null;
    }
}
