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
 * Configuration for vmocs (Virtual Machine in Compute via Slurm) support.
 *
 * <p>vmocs is a QEMU/KVM wrapper that launches and manages virtual machines inside Slurm jobs.
 * Each Jenkins build runs inside an isolated VM on the compute node, providing stronger isolation
 * than Pyxis containers — useful for workloads requiring custom kernels, full OS environments,
 * GPU passthrough via VFIO, or Windows guests.
 *
 * <p>The vmocs tool must be installed on the compute nodes and configured via {@code vmocs.yaml}
 * and {@code templates.yaml}. The plugin generates a batch script that:
 * <ol>
 *   <li>Launches the VM via {@code vmocs launch} (background, captures SSH connection info)</li>
 *   <li>Waits for SSH readiness</li>
 *   <li>Copies or locates {@code agent.jar} inside the VM</li>
 *   <li>Starts the Jenkins inbound agent inside the VM via SSH</li>
 *   <li>Waits for the build to complete (SSH session ends)</li>
 *   <li>Lets vmocs receive SIGTERM from Slurm for graceful shutdown</li>
 * </ol>
 *
 * @see <a href="https://naveenrajm7.github.io/vmocs/">vmocs Documentation</a>
 */
public class VmocsConfig extends AbstractDescribableImpl<VmocsConfig> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default vmocs binary name (resolved via PATH on the compute node). */
    public static final String DEFAULT_VMOCS_BIN = "vmocs";

    /** Default timeout in seconds to wait for the VM to become SSH-ready. */
    public static final int DEFAULT_VM_BOOT_TIMEOUT_SEC = 300;

    /** Path to agent.jar inside the standard jenkins/inbound-agent VM image, if pre-installed. */
    public static final String DEFAULT_VM_AGENT_JAR_PATH = "";

    @JsonProperty("template_name")
    private String templateName;

    @JsonProperty("vmocs_bin")
    private String vmocsBin;

    @JsonProperty("config_path")
    private String configPath;

    @JsonProperty("cores")
    private Integer cores;

    @JsonProperty("memory_mb")
    private Integer memoryMb;

    /**
     * Comma-separated list of PCI BDF addresses for device passthrough (e.g. GPU).
     * Each entry has the form {@code DDDD:BB:DD.F} (e.g. {@code 0000:03:00.0}).
     * Maps to one {@code --pci} flag per device in the {@code vmocs launch} invocation.
     */
    @JsonProperty("pci_devices")
    private String pciDevices;

    /**
     * Absolute path to a pre-installed {@code agent.jar} inside the VM image.
     * When empty the plugin downloads {@code agent.jar} from the Jenkins controller
     * and copies it into the VM via {@code scp} before starting the agent.
     */
    @JsonProperty("agent_jar_path")
    private String agentJarPath;

    /**
     * Seconds to wait for the VM to become SSH-ready after {@code vmocs launch}.
     * Defaults to {@value #DEFAULT_VM_BOOT_TIMEOUT_SEC}.
     */
    @JsonProperty("vm_boot_timeout_sec")
    private Integer vmBootTimeoutSec;

    @DataBoundConstructor
    public VmocsConfig() {
        this.templateName = "";
        this.vmocsBin = DEFAULT_VMOCS_BIN;
        this.configPath = "";
        this.cores = null;
        this.memoryMb = null;
        this.pciDevices = "";
        this.agentJarPath = DEFAULT_VM_AGENT_JAR_PATH;
        this.vmBootTimeoutSec = DEFAULT_VM_BOOT_TIMEOUT_SEC;
    }

    // -----------------------------------------------------------------------
    // templateName
    // -----------------------------------------------------------------------

    @CheckForNull
    public String getTemplateName() {
        return templateName;
    }

    @DataBoundSetter
    public void setTemplateName(String templateName) {
        this.templateName = templateName != null ? templateName.trim() : "";
    }

    // -----------------------------------------------------------------------
    // vmocsBin
    // -----------------------------------------------------------------------

    @NonNull
    public String getVmocsBin() {
        return (vmocsBin != null && !vmocsBin.trim().isEmpty()) ? vmocsBin.trim() : DEFAULT_VMOCS_BIN;
    }

    @DataBoundSetter
    public void setVmocsBin(String vmocsBin) {
        this.vmocsBin = (vmocsBin != null && !vmocsBin.trim().isEmpty()) ? vmocsBin.trim() : DEFAULT_VMOCS_BIN;
    }

    // -----------------------------------------------------------------------
    // configPath
    // -----------------------------------------------------------------------

    @CheckForNull
    public String getConfigPath() {
        return configPath;
    }

    @DataBoundSetter
    public void setConfigPath(String configPath) {
        this.configPath = configPath != null ? configPath.trim() : "";
    }

    // -----------------------------------------------------------------------
    // cores
    // -----------------------------------------------------------------------

    @CheckForNull
    public Integer getCores() {
        return cores;
    }

    @DataBoundSetter
    public void setCores(Integer cores) {
        this.cores = (cores != null && cores > 0) ? cores : null;
    }

    // -----------------------------------------------------------------------
    // memoryMb
    // -----------------------------------------------------------------------

    @CheckForNull
    public Integer getMemoryMb() {
        return memoryMb;
    }

    @DataBoundSetter
    public void setMemoryMb(Integer memoryMb) {
        this.memoryMb = (memoryMb != null && memoryMb > 0) ? memoryMb : null;
    }

    // -----------------------------------------------------------------------
    // pciDevices
    // -----------------------------------------------------------------------

    @CheckForNull
    public String getPciDevices() {
        return pciDevices;
    }

    @DataBoundSetter
    public void setPciDevices(String pciDevices) {
        this.pciDevices = pciDevices != null ? pciDevices.trim() : "";
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
    // Utility methods
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the minimum required field — {@code templateName} — is set.
     */
    public boolean isConfigured() {
        return templateName != null && !templateName.trim().isEmpty();
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
