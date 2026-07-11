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
 * Configuration for vmocs (VM On Cluster Scheduling) support.
 *
 * <p>vmocs is a Slurm SPANK plugin that transparently launches and manages QEMU/KVM virtual
 * machines as Slurm jobs. The plugin registers the {@code --vm-image=} sbatch argument. When
 * Slurm runs the job, the SPANK plugin starts the VM (via the vmocs CLI on the compute node),
 * waits for it to become ready, and then executes the user's batch script.
 *
 * <p>Because the VM lifecycle is managed entirely by the SPANK plugin, our batch script does
 * <em>not</em> invoke {@code vmocs} directly. Instead it:
 * <ol>
 *   <li>Includes {@code #SBATCH --vm-image=<vmImage>} in the script header so Slurm/SPANK
 *       knows which image to start.</li>
 *   <li>Waits until the VM's SSH port (by default 60222 on localhost) is reachable — the SPANK
 *       plugin prints {@code "VM ready ... ssh -i <key> -p <port> <user>@127.0.0.1"} to stdout
 *       when the VM is up.</li>
 *   <li>Downloads or locates {@code agent.jar} and copies it into the VM via {@code scp}.</li>
 *   <li>Starts the Jenkins inbound agent inside the VM via SSH, blocking until the build
 *       completes.</li>
 *   <li>Returns — the batch script exits, Slurm terminates the job, and the SPANK plugin
 *       shuts the VM down.</li>
 * </ol>
 *
 * <p>Resource allocation (CPUs, memory, GPUs, partition, account…) is handled by the standard
 * {@link SlurmJobTemplate} fields — not by vmocs. The only vmocs-specific input is the VM image
 * name.
 *
 * @see <a href="https://naveenrajm7.github.io/vmocs/">vmocs Documentation</a>
 */
public class VmocsConfig extends AbstractDescribableImpl<VmocsConfig> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default SSH user inside vmocs-managed VMs (Vagrant convention). */
    public static final String DEFAULT_SSH_USER = "vagrant";

    /** Default SSH port forwarded to the VM on the compute node. */
    public static final int DEFAULT_SSH_PORT = 60222;

    /** Default boot-readiness timeout in seconds. */
    public static final int DEFAULT_VM_BOOT_TIMEOUT_SEC = 300;

    /**
     * VM image name passed as {@code --vm-image=<vmImage>} to the vmocs SPANK plugin via the
     * {@code #SBATCH} directive in the batch script header.
     *
     * <p>Must match a template name known to the vmocs installation on the target partition
     * (e.g. {@code windows11-gfx1101}, {@code base-ubuntu}).
     */
    @JsonProperty("vm_image")
    private String vmImage;

    /**
     * SSH user inside the VM.  Defaults to {@value #DEFAULT_SSH_USER} (standard Vagrant
     * convention used by vmocs-managed images).
     */
    @JsonProperty("ssh_user")
    private String sshUser;

    /**
     * Host port on the compute node forwarded to the VM's SSH port (22).
     * Defaults to {@value #DEFAULT_SSH_PORT}.  The vmocs SPANK plugin allocates this port
     * when it starts the VM; the default matches the vmocs default port range.
     */
    @JsonProperty("ssh_port")
    private Integer sshPort;

    /**
     * Absolute path to the SSH private key on the compute node used to log in to the VM.
     * When empty, the SSH command uses the default key resolution (agent/config).
     *
     * <p>For Vagrant-based images the vmocs insecure key is typically located at
     * {@code <vmocs_lib>/keys/vagrant_insecure_key}; set this path here so the script
     * can authenticate without a password.
     */
    @JsonProperty("ssh_key_path")
    private String sshKeyPath;

    /**
     * Absolute path to a pre-installed {@code agent.jar} <em>inside the VM image</em>.
     *
     * <p>When set the plugin uses this path directly — no download or {@code scp} is needed.
     * When empty the plugin downloads {@code agent.jar} from the Jenkins controller at job
     * start and copies it into the VM via {@code scp} before launching the agent.
     */
    @JsonProperty("agent_jar_path")
    private String agentJarPath;

    /**
     * Maximum seconds to wait for the VM's SSH port to become reachable after the SPANK
     * plugin starts the VM.  Defaults to {@value #DEFAULT_VM_BOOT_TIMEOUT_SEC}.
     * Increase this for images that boot slowly or lack a vmocs snapshot.
     */
    @JsonProperty("vm_boot_timeout_sec")
    private Integer vmBootTimeoutSec;

    @DataBoundConstructor
    public VmocsConfig() {
        this.vmImage = "";
        this.sshUser = DEFAULT_SSH_USER;
        this.sshPort = DEFAULT_SSH_PORT;
        this.sshKeyPath = "";
        this.agentJarPath = "";
        this.vmBootTimeoutSec = DEFAULT_VM_BOOT_TIMEOUT_SEC;
    }

    // -----------------------------------------------------------------------
    // vmImage
    // -----------------------------------------------------------------------

    @CheckForNull
    public String getVmImage() {
        return vmImage;
    }

    @DataBoundSetter
    public void setVmImage(String vmImage) {
        this.vmImage = vmImage != null ? vmImage.trim() : "";
    }

    // -----------------------------------------------------------------------
    // sshUser
    // -----------------------------------------------------------------------

    @NonNull
    public String getSshUser() {
        return (sshUser != null && !sshUser.trim().isEmpty()) ? sshUser.trim() : DEFAULT_SSH_USER;
    }

    @DataBoundSetter
    public void setSshUser(String sshUser) {
        this.sshUser = (sshUser != null && !sshUser.trim().isEmpty()) ? sshUser.trim() : DEFAULT_SSH_USER;
    }

    // -----------------------------------------------------------------------
    // sshPort
    // -----------------------------------------------------------------------

    public int getSshPort() {
        return (sshPort != null && sshPort > 0) ? sshPort : DEFAULT_SSH_PORT;
    }

    @DataBoundSetter
    public void setSshPort(Integer sshPort) {
        this.sshPort = (sshPort != null && sshPort > 0) ? sshPort : DEFAULT_SSH_PORT;
    }

    // -----------------------------------------------------------------------
    // sshKeyPath
    // -----------------------------------------------------------------------

    @CheckForNull
    public String getSshKeyPath() {
        return sshKeyPath;
    }

    @DataBoundSetter
    public void setSshKeyPath(String sshKeyPath) {
        this.sshKeyPath = sshKeyPath != null ? sshKeyPath.trim() : "";
    }

    // -----------------------------------------------------------------------
    // agentJarPath
    // -----------------------------------------------------------------------

    @CheckForNull
    public String getAgentJarPath() {
        return agentJarPath;
    }

    @DataBoundSetter
    public void setAgentJarPath(String agentJarPath) {
        this.agentJarPath = agentJarPath != null ? agentJarPath.trim() : "";
    }

    // -----------------------------------------------------------------------
    // vmBootTimeoutSec
    // -----------------------------------------------------------------------

    public int getVmBootTimeoutSec() {
        return (vmBootTimeoutSec != null && vmBootTimeoutSec > 0) ? vmBootTimeoutSec : DEFAULT_VM_BOOT_TIMEOUT_SEC;
    }

    @DataBoundSetter
    public void setVmBootTimeoutSec(Integer vmBootTimeoutSec) {
        this.vmBootTimeoutSec =
                (vmBootTimeoutSec != null && vmBootTimeoutSec > 0) ? vmBootTimeoutSec : DEFAULT_VM_BOOT_TIMEOUT_SEC;
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the minimum required field — {@code vmImage} — is non-empty.
     */
    public boolean isConfigured() {
        return vmImage != null && !vmImage.trim().isEmpty();
    }

    @Extension
    @Symbol("vmocs")
    public static class DescriptorImpl extends Descriptor<VmocsConfig> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "vmocs VM Configuration";
        }
    }
}
