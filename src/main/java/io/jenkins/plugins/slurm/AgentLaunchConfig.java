package io.jenkins.plugins.slurm;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.io.Serializable;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Configuration for launching the Jenkins inbound agent directly on Slurm compute nodes
 * (without Pyxis/Enroot containers).
 *
 * <p>When Pyxis is not configured, the plugin requires either a pre-installed {@code agent.jar}
 * path or {@code downloadJar=true} to fetch the JAR from the Jenkins controller at job start.
 */
public class AgentLaunchConfig extends AbstractDescribableImpl<AgentLaunchConfig> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default java command when running on the host (not inside inbound-agent image). */
    public static final String DEFAULT_JAVA_PATH = "java";

    /** Java path inside the standard jenkins/inbound-agent container image. */
    public static final String CONTAINER_JAVA_PATH = "/opt/java/openjdk/bin/java";

    /** agent.jar path inside the standard jenkins/inbound-agent container image. */
    public static final String CONTAINER_JAR_PATH = "/usr/share/jenkins/agent.jar";

    @JsonProperty("java_path")
    private String javaPath;

    @JsonProperty("jar_path")
    private String jarPath;

    @JsonProperty("download_jar")
    private Boolean downloadJar;

    @JsonProperty("setup_script")
    private String setupScript;

    @DataBoundConstructor
    public AgentLaunchConfig() {
        this.javaPath = DEFAULT_JAVA_PATH;
        this.jarPath = "";
        this.downloadJar = false;
        this.setupScript = "";
    }

    @NonNull
    public String getJavaPath() {
        return javaPath != null && !javaPath.trim().isEmpty() ? javaPath.trim() : DEFAULT_JAVA_PATH;
    }

    @DataBoundSetter
    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath != null ? javaPath : DEFAULT_JAVA_PATH;
    }

    @CheckForNull
    public String getJarPath() {
        return jarPath;
    }

    @DataBoundSetter
    public void setJarPath(String jarPath) {
        this.jarPath = jarPath != null ? jarPath : "";
    }

    public boolean getDownloadJar() {
        return downloadJar != null && downloadJar;
    }

    @DataBoundSetter
    public void setDownloadJar(Boolean downloadJar) {
        this.downloadJar = downloadJar != null && downloadJar;
    }

    @CheckForNull
    public String getSetupScript() {
        return setupScript;
    }

    @DataBoundSetter
    public void setSetupScript(String setupScript) {
        this.setupScript = setupScript != null ? setupScript : "";
    }

    /**
     * Returns true when native (non-container) launch settings are sufficient to start an agent.
     */
    public boolean isConfigured() {
        return getDownloadJar() || (jarPath != null && !jarPath.trim().isEmpty());
    }

    /**
     * Validates that this config can launch an agent without Pyxis.
     */
    public void validateNativeLaunch() {
        if (!isConfigured()) {
            throw new IllegalStateException("Native agent launch requires agent.jarPath or agent.downloadJar=true. "
                    + "Configure Agent Launch on the cloud or template, or enable Pyxis container support.");
        }
    }

    /**
     * Merges cloud-level defaults with template-level overrides.
     * Template wins for any field it explicitly sets (non-empty jarPath/setupScript,
     * downloadJar=true, or non-default javaPath).
     */
    @CheckForNull
    public static AgentLaunchConfig merge(
            @CheckForNull AgentLaunchConfig cloud, @CheckForNull AgentLaunchConfig template) {
        if (cloud == null && template == null) {
            return null;
        }
        if (template == null) {
            return copy(cloud);
        }
        if (cloud == null) {
            return copy(template);
        }

        AgentLaunchConfig merged = copy(cloud);
        if (template.getJarPath() != null && !template.getJarPath().trim().isEmpty()) {
            merged.setJarPath(template.getJarPath());
        }
        if (template.getDownloadJar()) {
            merged.setDownloadJar(true);
        }
        if (template.getSetupScript() != null
                && !template.getSetupScript().trim().isEmpty()) {
            merged.setSetupScript(template.getSetupScript());
        }
        if (!DEFAULT_JAVA_PATH.equals(template.getJavaPath())) {
            merged.setJavaPath(template.getJavaPath());
        }
        return merged;
    }

    @CheckForNull
    private static AgentLaunchConfig copy(@CheckForNull AgentLaunchConfig source) {
        if (source == null) {
            return null;
        }
        AgentLaunchConfig copy = new AgentLaunchConfig();
        copy.setJavaPath(source.getJavaPath());
        copy.setJarPath(source.getJarPath());
        copy.setDownloadJar(source.getDownloadJar());
        copy.setSetupScript(source.getSetupScript());
        return copy;
    }

    @Extension
    @Symbol("agent")
    public static class DescriptorImpl extends Descriptor<AgentLaunchConfig> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Agent Launch (native)";
        }
    }
}
