package io.jenkins.plugins.slurm;

import com.fasterxml.jackson.annotation.JsonProperty;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.Serializable;

/**
 * Configuration for Pyxis/Enroot container support in Slurm.
 * Pyxis is a Slurm plugin that allows running containers (Docker, Singularity, etc.)
 * within Slurm jobs using Enroot as the container runtime.
 * 
 * These settings map to Pyxis command-line arguments and are translated to
 * Slurm environment variables or script directives during job submission.
 * 
 * Supports both JSON API format (container_image) and Jenkins UI format (containerImage).
 * 
 * @see <a href="https://github.com/NVIDIA/pyxis">Pyxis Documentation</a>
 */
public class PyxisConfig extends AbstractDescribableImpl<PyxisConfig> implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    @JsonProperty("container_image")  // REST API style
    private String containerImage;
    
    @JsonProperty("container_mounts")
    private String containerMounts;
    
    @JsonProperty("container_mount_home")
    private Boolean containerMountHome;
    
    @JsonProperty("container_workdir")
    private String containerWorkdir;
    
    @JsonProperty("container_name")
    private String containerName;
    
    @JsonProperty("container_remap_root")
    private Boolean containerRemap;
    
    @JsonProperty("container_save")
    private String containerSave;
    
    @JsonProperty("container_writable")
    private Boolean containerWritable;
    
    @JsonProperty("container_entrypoint")
    private String containerEntrypoint;
    
    @JsonProperty("container_env")
    private String containerEnv;
    
    @JsonProperty("container_readonly")
    private Boolean containerReadonly;
    
    @DataBoundConstructor
    public PyxisConfig() {
        this.containerImage = "";
        this.containerMounts = "";
        this.containerMountHome = true;  // Default: mount home
        this.containerWorkdir = "";
        this.containerName = "";
        this.containerRemap = false;  // Default: no remap
        this.containerSave = "";
        this.containerWritable = false;
        this.containerEntrypoint = "";
        this.containerEnv = "";
        this.containerReadonly = false;
    }
    
    // Container Image
    @CheckForNull
    public String getContainerImage() {
        return containerImage;
    }
    
    @DataBoundSetter
    public void setContainerImage(String containerImage) {
        this.containerImage = containerImage != null ? containerImage : "";
    }
    
    // Container Mounts
    @CheckForNull
    public String getContainerMounts() {
        return containerMounts;
    }
    
    @DataBoundSetter
    public void setContainerMounts(String containerMounts) {
        this.containerMounts = containerMounts != null ? containerMounts : "";
    }
    
    // Mount Home Directory
    public boolean getContainerMountHome() {
        return containerMountHome != null ? containerMountHome : true;
    }
    
    @DataBoundSetter
    public void setContainerMountHome(Boolean containerMountHome) {
        this.containerMountHome = containerMountHome != null ? containerMountHome : true;
    }
    
    // Container Working Directory
    @CheckForNull
    public String getContainerWorkdir() {
        return containerWorkdir;
    }
    
    @DataBoundSetter
    public void setContainerWorkdir(String containerWorkdir) {
        this.containerWorkdir = containerWorkdir != null ? containerWorkdir : "";
    }
    
    // Container Name
    @CheckForNull
    public String getContainerName() {
        return containerName;
    }
    
    @DataBoundSetter
    public void setContainerName(String containerName) {
        this.containerName = containerName != null ? containerName : "";
    }
    
    // Container Remap Root
    public boolean getContainerRemap() {
        return containerRemap != null ? containerRemap : false;
    }
    
    @DataBoundSetter
    public void setContainerRemap(Boolean containerRemap) {
        this.containerRemap = containerRemap != null ? containerRemap : false;
    }
    
    // Container Save
    @CheckForNull
    public String getContainerSave() {
        return containerSave;
    }
    
    @DataBoundSetter
    public void setContainerSave(String containerSave) {
        this.containerSave = containerSave != null ? containerSave : "";
    }
    
    // Container Writable
    public boolean getContainerWritable() {
        return containerWritable != null ? containerWritable : false;
    }
    
    @DataBoundSetter
    public void setContainerWritable(Boolean containerWritable) {
        this.containerWritable = containerWritable != null ? containerWritable : false;
    }
    
    // Container Readonly
    public boolean getContainerReadonly() {
        return containerReadonly != null ? containerReadonly : false;
    }
    
    @DataBoundSetter
    public void setContainerReadonly(Boolean containerReadonly) {
        this.containerReadonly = containerReadonly != null ? containerReadonly : false;
    }
    
    // Container Entrypoint
    @CheckForNull
    public String getContainerEntrypoint() {
        return containerEntrypoint;
    }
    
    @DataBoundSetter
    public void setContainerEntrypoint(String containerEntrypoint) {
        this.containerEntrypoint = containerEntrypoint != null ? containerEntrypoint : "";
    }
    
    // Container Environment Variables
    @CheckForNull
    public String getContainerEnv() {
        return containerEnv;
    }
    
    @DataBoundSetter
    public void setContainerEnv(String containerEnv) {
        this.containerEnv = containerEnv != null ? containerEnv : "";
    }
    
    /**
     * Check if any Pyxis configuration is set
     */
    public boolean isConfigured() {
        return (containerImage != null && !containerImage.trim().isEmpty());
    }
    
    /**
     * Check if this configuration is equivalent to another
     */
    public boolean isEquivalentTo(PyxisConfig other) {
        if (other == null) return false;
        return equals(this.containerImage, other.containerImage) &&
               equals(this.containerMounts, other.containerMounts) &&
               equals(this.containerMountHome, other.containerMountHome) &&
               equals(this.containerWorkdir, other.containerWorkdir) &&
               equals(this.containerName, other.containerName) &&
               equals(this.containerRemap, other.containerRemap) &&
               equals(this.containerSave, other.containerSave) &&
               equals(this.containerWritable, other.containerWritable) &&
               equals(this.containerReadonly, other.containerReadonly) &&
               equals(this.containerEntrypoint, other.containerEntrypoint) &&
               equals(this.containerEnv, other.containerEnv);
    }
    
    private boolean equals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
    
    @Extension
    @Symbol("pyxis")
    public static class DescriptorImpl extends Descriptor<PyxisConfig> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Pyxis Container Configuration";
        }
    }
}
