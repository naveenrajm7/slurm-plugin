package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Resolves the launch mode and settings a template uses on a cloud.
 *
 * <p>A template that saved Native, Pyxis, or VMoCS uses that mode. A template that did not save a
 * mode keeps a legacy Pyxis image when it has one, and otherwise uses the cloud default. Within
 * the chosen mode, a value set on the template replaces the cloud value.
 */
final class LaunchDefaults {

    private LaunchDefaults() {}

    @NonNull
    static String effectiveMode(@CheckForNull SlurmCloud cloud, @NonNull SlurmJobTemplate template) {
        if (template.isExplicitLaunchMode()) {
            return template.getLaunchMode();
        }
        if (template.getPyxis() != null && template.getPyxis().isConfigured()) {
            return "PYXIS";
        }
        String cloudMode = cloudMode(cloud);
        return cloudMode != null ? cloudMode : "NATIVE";
    }

    @CheckForNull
    static AgentLaunchConfig effectiveAgent(@CheckForNull SlurmCloud cloud, @NonNull SlurmJobTemplate template) {
        return AgentLaunchConfig.merge(cloud != null ? cloud.getAgent() : null, template.getAgent());
    }

    @CheckForNull
    static PyxisConfig effectivePyxis(@CheckForNull SlurmCloud cloud, @NonNull SlurmJobTemplate template) {
        return PyxisConfig.merge(cloud != null ? cloud.getPyxis() : null, template.getPyxis());
    }

    @CheckForNull
    static VmocsConfig effectiveVmocs(@CheckForNull SlurmCloud cloud, @NonNull SlurmJobTemplate template) {
        return VmocsConfig.merge(cloud != null ? cloud.getVmocs() : null, template.getVmocs());
    }

    /**
     * Mode a cloud contributes when the template did not choose one. Null when the cloud has no
     * launch default at all.
     */
    @CheckForNull
    private static String cloudMode(@CheckForNull SlurmCloud cloud) {
        if (cloud == null) {
            return null;
        }
        if (cloud.isExplicitLaunchMode()) {
            return cloud.getLaunchMode();
        }
        if (cloud.getVmocs() != null && cloud.getVmocs().isConfigured()) {
            return "VMOCS";
        }
        if (cloud.getPyxis() != null && cloud.getPyxis().isConfigured()) {
            return "PYXIS";
        }
        if (cloud.getAgent() != null && cloud.getAgent().isConfigured()) {
            return "NATIVE";
        }
        return null;
    }
}
