package io.jenkins.plugins.slurm;

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
 * Configuration for Pyxis/Enroot container support in SLURM.
 * Pyxis is a SLURM plugin that allows running containers (Docker, Singularity, etc.)
 * within SLURM jobs using Enroot as the container runtime.
 * 
 * These settings map to Pyxis command-line arguments and are translated to
 * SLURM environment variables or script directives during job submission.
 * 
 * @see <a href="https://github.com/NVIDIA/pyxis">Pyxis Documentation</a>
 */
public class PyxisConfig extends AbstractDescribableImpl<PyxisConfig> implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String containerImage;
    private String containerMounts;
    private Boolean containerMountHome;
    private String containerWorkdir;
    private String containerName;
    private Boolean containerRemap;
    private String containerSave;
    private Boolean containerWritable;
    private String containerEntrypoint;
    
    @DataBoundConstructor
    public PyxisConfig() {
        this.containerImage = "";
        this.containerMounts = "";
        this.containerMountHome = true;  // Default: mount home
        this.containerWorkdir = "";
        this.containerName = "";
        this.containerRemap = null;
        this.containerSave = "";
        this.containerWritable = false;
        this.containerEntrypoint = "";
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
    @CheckForNull
    public Boolean getContainerMountHome() {
        return containerMountHome;
    }
    
    @DataBoundSetter
    public void setContainerMountHome(Boolean containerMountHome) {
        this.containerMountHome = containerMountHome;
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
    
    // Container Remap
    @CheckForNull
    public Boolean getContainerRemap() {
        return containerRemap;
    }
    
    @DataBoundSetter
    public void setContainerRemap(Boolean containerRemap) {
        this.containerRemap = containerRemap;
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
    @CheckForNull
    public Boolean getContainerWritable() {
        return containerWritable;
    }
    
    @DataBoundSetter
    public void setContainerWritable(Boolean containerWritable) {
        this.containerWritable = containerWritable;
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
               equals(this.containerEntrypoint, other.containerEntrypoint);
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
