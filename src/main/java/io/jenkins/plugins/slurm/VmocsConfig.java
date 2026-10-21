package io.jenkins.plugins.slurm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Configuration for launching a Jenkins inbound agent inside a VMoCS virtual machine.
 *
 * <p>The VM template name is passed to {@code srun --vm-image}. Connection settings are delivered
 * through selected environment forwarding ({@code --vm-forward-env}); VMoCS 0.1.3 or newer is
 * required because earlier releases do not preserve repeated forwarding options. VMoCS accepts at
 * most 16 forwarded variables per step.
 */
public class VmocsConfig extends AbstractDescribableImpl<VmocsConfig> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** VMoCS SPANK limit for {@code --vm-forward-env} on one step. */
    public static final int MAX_FORWARDED_VARIABLES = 16;

    static final List<String> REQUIRED_FORWARDED_VARIABLES =
            List.of("JENKINS_URL", "JENKINS_SECRET", "JENKINS_AGENT_NAME", "SLURM_JOB_ID");

    private static final Pattern TEMPLATE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @JsonProperty("vm_image")
    @JsonAlias("vmImage")
    private String vmImage;

    @JsonProperty("java_path")
    @JsonAlias("javaPath")
    private String javaPath;

    @JsonProperty("jar_path")
    @JsonAlias("jarPath")
    private String jarPath;

    @JsonProperty("work_dir")
    @JsonAlias("workDir")
    private String workDir;

    @JsonProperty("forward_env")
    @JsonAlias("forwardEnv")
    private String forwardEnv;

    @DataBoundConstructor
    public VmocsConfig() {
        this.vmImage = "";
        this.javaPath = AgentLaunchConfig.DEFAULT_JAVA_PATH;
        this.jarPath = "";
        this.workDir = "/tmp/jenkins";
        this.forwardEnv = "";
    }

    @CheckForNull
    public String getVmImage() {
        return vmImage;
    }

    @DataBoundSetter
    public void setVmImage(String vmImage) {
        this.vmImage = vmImage != null ? vmImage.trim() : "";
    }

    @NonNull
    public String getJavaPath() {
        return javaPath != null && !javaPath.trim().isEmpty()
                ? javaPath.trim()
                : AgentLaunchConfig.DEFAULT_JAVA_PATH;
    }

    @DataBoundSetter
    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath != null ? javaPath : AgentLaunchConfig.DEFAULT_JAVA_PATH;
    }

    @CheckForNull
    public String getJarPath() {
        return jarPath;
    }

    @DataBoundSetter
    public void setJarPath(String jarPath) {
        this.jarPath = jarPath != null ? jarPath.trim() : "";
    }

    @NonNull
    public String getWorkDir() {
        return workDir != null && !workDir.trim().isEmpty() ? workDir.trim() : "/tmp/jenkins";
    }

    @DataBoundSetter
    public void setWorkDir(String workDir) {
        this.workDir = workDir != null ? workDir : "/tmp/jenkins";
    }

    @CheckForNull
    public String getForwardEnv() {
        return forwardEnv;
    }

    @DataBoundSetter
    public void setForwardEnv(String forwardEnv) {
        this.forwardEnv = forwardEnv != null ? forwardEnv : "";
    }

    /** A VMoCS template name is the only required setting. */
    public boolean isConfigured() {
        return vmImage != null && !vmImage.trim().isEmpty();
    }

    public boolean hasPreinstalledJar() {
        return jarPath != null && !jarPath.trim().isEmpty();
    }

    /**
     * Additional variable names to forward when the template environment supplies them.
     *
     * @throws IllegalArgumentException when a name is invalid, repeated, reserved, or the total
     *     exceeds the VMoCS limit
     */
    @NonNull
    public List<String> customForwardEnvironmentNames() {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (forwardEnv != null) {
            for (String token : forwardEnv.split(",")) {
                String name = token.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException(
                            "VMoCS forwarded environment name is invalid: " + name);
                }
                if (REQUIRED_FORWARDED_VARIABLES.contains(name)) {
                    throw new IllegalArgumentException(
                            "VMoCS already forwards " + name + ". Remove it from the additional environment list.");
                }
                if (!seen.add(name)) {
                    throw new IllegalArgumentException("VMoCS forwarded environment name is repeated: " + name);
                }
                names.add(name);
            }
        }
        int total = REQUIRED_FORWARDED_VARIABLES.size() + names.size();
        if (total > MAX_FORWARDED_VARIABLES) {
            throw new IllegalArgumentException("VMoCS can forward at most " + MAX_FORWARDED_VARIABLES
                    + " variables. Required connection variables use " + REQUIRED_FORWARDED_VARIABLES.size()
                    + ", leaving room for " + (MAX_FORWARDED_VARIABLES - REQUIRED_FORWARDED_VARIABLES.size())
                    + " additional names.");
        }
        return names;
    }

    /**
     * Merges cloud defaults with a template override. A blank template image, JAR path, or
     * forward list keeps the cloud value. Java path and work directory keep the cloud value when
     * the template still has the built-in default.
     */
    @CheckForNull
    public static VmocsConfig merge(@CheckForNull VmocsConfig cloud, @CheckForNull VmocsConfig template) {
        if (template == null) {
            return copy(cloud);
        }
        if (cloud == null) {
            return copy(template);
        }
        VmocsConfig merged = copy(cloud);
        if (template.isConfigured()) {
            merged.setVmImage(template.getVmImage());
        }
        if (!AgentLaunchConfig.DEFAULT_JAVA_PATH.equals(template.getJavaPath())) {
            merged.setJavaPath(template.getJavaPath());
        }
        if (template.hasPreinstalledJar()) {
            merged.setJarPath(template.getJarPath());
        }
        if (!"/tmp/jenkins".equals(template.getWorkDir())) {
            merged.setWorkDir(template.getWorkDir());
        }
        if (template.getForwardEnv() != null && !template.getForwardEnv().trim().isEmpty()) {
            merged.setForwardEnv(template.getForwardEnv());
        }
        return merged;
    }

    public static VmocsConfig copy(@CheckForNull VmocsConfig source) {
        if (source == null) {
            return null;
        }
        VmocsConfig copy = new VmocsConfig();
        copy.setVmImage(source.getVmImage());
        copy.setJavaPath(source.getJavaPath());
        copy.setJarPath(source.getJarPath());
        copy.setWorkDir(source.getWorkDir());
        copy.setForwardEnv(source.getForwardEnv());
        return copy;
    }

    /**
     * Validates the guest launch contract: template name, absolute guest paths, and the forwarding
     * allowlist.
     */
    public void validate() {
        if (!isConfigured()) {
            throw new IllegalArgumentException(
                    "VMoCS launch mode requires a VM template name. Set the VMoCS image, or select Native or Pyxis launch.");
        }
        if (!TEMPLATE_NAME.matcher(vmImage.trim()).matches()) {
            throw new IllegalArgumentException(
                    "VMoCS image must be a template name (letters, numbers, '.', '_' or '-'), not a container reference: "
                            + vmImage.trim());
        }
        rejectNewlines("Java path", getJavaPath());
        if (hasPreinstalledJar()) {
            rejectNewlines("Agent JAR path", jarPath.trim());
            if (!jarPath.trim().startsWith("/")) {
                throw new IllegalArgumentException("VMoCS agent JAR path must be an absolute guest path.");
            }
        }
        rejectNewlines("Work directory", getWorkDir());
        if (!getWorkDir().startsWith("/")) {
            throw new IllegalArgumentException("VMoCS work directory must be an absolute guest path.");
        }
        customForwardEnvironmentNames();
    }

    private static void rejectNewlines(String label, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must not contain newlines.");
        }
    }

    @Extension
    @Symbol("vmocs")
    public static class DescriptorImpl extends Descriptor<VmocsConfig> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "VMoCS Virtual Machine";
        }

        @RequirePOST
        public FormValidation doCheckVmImage(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.MANAGE);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("VMoCS template name is required.");
            }
            if (!TEMPLATE_NAME.matcher(value.trim()).matches()) {
                return FormValidation.error(
                        "Enter the VMoCS template name, for example base-ubuntu. Container references are not accepted.");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckWorkDir(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.MANAGE);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok("The guest work directory defaults to /tmp/jenkins.");
            }
            if (!value.trim().startsWith("/")) {
                return FormValidation.error("Work directory must be an absolute path inside the guest.");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckForwardEnv(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.MANAGE);
            VmocsConfig config = new VmocsConfig();
            config.setForwardEnv(value);
            try {
                config.customForwardEnvironmentNames();
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
