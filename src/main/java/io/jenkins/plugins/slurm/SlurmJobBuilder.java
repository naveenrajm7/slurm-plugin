package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.slurm.client.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Builder class for creating Slurm job descriptions.
 *
 * This class takes a {@link SlurmJobTemplate} and converts it into a
 * {@link JobDescMsg} suitable for submission to the Slurm REST API.
 *
 * Similar to Kubernetes plugin's PodTemplateBuilder.
 */
public class SlurmJobBuilder {

    private static final Logger LOGGER = Logger.getLogger(SlurmJobBuilder.class.getName());

    private final SlurmJobTemplate template;
    private final String agentName;
    private final String jenkinsUrl;
    private final String agentSecret;
    private final AgentLaunchConfig cloudAgent;

    /**
     * Creates a new job builder.
     *
     * @param template The job template to build from
     * @param agentName The unique name for this agent/job
     * @param jenkinsUrl The Jenkins URL for agent connection
     * @param agentSecret The secret for agent authentication
     */
    public SlurmJobBuilder(
            @NonNull SlurmJobTemplate template,
            @NonNull String agentName,
            @NonNull String jenkinsUrl,
            @CheckForNull String agentSecret) {
        this(template, agentName, jenkinsUrl, agentSecret, null);
    }

    /**
     * Creates a new job builder with cloud-level agent launch defaults.
     */
    public SlurmJobBuilder(
            @NonNull SlurmJobTemplate template,
            @NonNull String agentName,
            @NonNull String jenkinsUrl,
            @CheckForNull String agentSecret,
            @CheckForNull AgentLaunchConfig cloudAgent) {
        this.template = template;
        this.agentName = agentName;
        this.jenkinsUrl = jenkinsUrl;
        this.agentSecret = agentSecret;
        this.cloudAgent = cloudAgent;
    }

    /**
     * Builds the JobDescMsg for submission to Slurm.
     *
     * @return The job description message ready for submission
     */
    @NonNull
    public JobDescMsg build() {
        LOGGER.info("Building Slurm job for agent: " + agentName);

        JobDescMsg jobDesc = new JobDescMsg();

        // Set job name (required)
        jobDesc.setName(agentName);

        // Set partition if specified
        if (template.getPartition() != null && !template.getPartition().trim().isEmpty()) {
            jobDesc.setPartition(template.getPartition());
        }

        // Set working directory
        if (template.getCurrentWorkingDirectory() != null
                && !template.getCurrentWorkingDirectory().trim().isEmpty()) {
            jobDesc.setCurrentWorkingDirectory(template.getCurrentWorkingDirectory());
        }

        // Set CPU resources
        if (template.getCpusPerTask() != null && template.getCpusPerTask() > 0) {
            jobDesc.setCpusPerTask(template.getCpusPerTask());
        }

        // Set memory per node (in MB)
        if (template.getMemoryPerNode() != null && template.getMemoryPerNode() > 0) {
            Uint64NoValStruct memory = new Uint64NoValStruct();
            memory.setSet(true);
            memory.setInfinite(false);
            memory.setNumber(template.getMemoryPerNode());
            jobDesc.setMemoryPerNode(memory);
        }

        // Set time limit (in minutes)
        if (template.getTimeLimit() != null && template.getTimeLimit() > 0) {
            Uint32NoValStruct time = new Uint32NoValStruct();
            time.setSet(true);
            time.setInfinite(false);
            time.setNumber(template.getTimeLimit());
            jobDesc.setTimeLimit(time);
        }

        // Set node count
        if (template.getMinimumNodes() != null && template.getMinimumNodes() > 0) {
            jobDesc.setMinimumNodes(template.getMinimumNodes());
        }

        // Set task count
        if (template.getTasks() != null && template.getTasks() > 0) {
            jobDesc.setTasks(template.getTasks());
        }

        // Set TRES for GPUs and other trackable resources
        if (template.getTresPerJob() != null && !template.getTresPerJob().trim().isEmpty()) {
            jobDesc.setTresPerJob(template.getTresPerJob());
        }
        if (template.getTresPerNode() != null
                && !template.getTresPerNode().trim().isEmpty()) {
            jobDesc.setTresPerNode(template.getTresPerNode());
        }
        if (template.getTresPerTask() != null
                && !template.getTresPerTask().trim().isEmpty()) {
            jobDesc.setTresPerTask(template.getTresPerTask());
        }

        // Set account if specified
        if (template.getAccount() != null && !template.getAccount().trim().isEmpty()) {
            jobDesc.setAccount(template.getAccount());
        }

        // Set QoS if specified
        if (template.getQos() != null && !template.getQos().trim().isEmpty()) {
            jobDesc.setQos(template.getQos());
        }

        // Set constraints (features) if specified
        if (template.getConstraints() != null
                && !template.getConstraints().trim().isEmpty()) {
            jobDesc.setConstraints(template.getConstraints());
        }

        // Set required nodes (REST API: required_nodes array)
        if (template.getRequiredNodes() != null
                && !template.getRequiredNodes().trim().isEmpty()) {
            List<String> requiredNodesList = new ArrayList<>();
            for (String node : template.getRequiredNodes().split(",")) {
                String trimmed = node.trim();
                if (!trimmed.isEmpty()) {
                    requiredNodesList.add(trimmed);
                }
            }
            if (!requiredNodesList.isEmpty()) {
                jobDesc.setRequiredNodes(requiredNodesList);
                LOGGER.fine("Set required_nodes: " + requiredNodesList);
            }
        }

        // Set excluded nodes (REST API: excluded_nodes array)
        if (template.getExcludedNodes() != null
                && !template.getExcludedNodes().trim().isEmpty()) {
            List<String> excludedNodesList = new ArrayList<>();
            for (String node : template.getExcludedNodes().split(",")) {
                String trimmed = node.trim();
                if (!trimmed.isEmpty()) {
                    excludedNodesList.add(trimmed);
                }
            }
            if (!excludedNodesList.isEmpty()) {
                jobDesc.setExcludedNodes(excludedNodesList);
                LOGGER.fine("Set excluded_nodes: " + excludedNodesList);
            }
        }

        applyOptionalString(jobDesc::setComment, template.getComment());
        applyOptionalString(jobDesc::setPrefer, template.getPrefer());
        applyOptionalString(jobDesc::setReservation, template.getReservation());
        applyOptionalString(jobDesc::setNetwork, template.getNetwork());
        applyOptionalString(jobDesc::setNodes, template.getNodes());
        applyOptionalString(jobDesc::setTresPerSocket, template.getTresPerSocket());
        applyOptionalString(jobDesc::setTresBind, template.getTresBind());
        applyOptionalString(jobDesc::setTresFreq, template.getTresFreq());
        applyOptionalString(jobDesc::setStandardOutput, template.getStandardOutput());
        applyOptionalString(jobDesc::setStandardError, template.getStandardError());
        applyOptionalString(jobDesc::setStandardInput, template.getStandardInput());

        if (template.getNice() != null) {
            jobDesc.setNice(template.getNice());
        }
        if (template.getReboot() != null) {
            jobDesc.setReboot(template.getReboot());
        }
        if (template.getTimeMinimum() != null && template.getTimeMinimum() > 0) {
            jobDesc.setTimeMinimum(uint32(template.getTimeMinimum()));
        }
        if (template.getMaximumNodes() != null && template.getMaximumNodes() > 0) {
            jobDesc.setMaximumNodes(template.getMaximumNodes());
        }
        if (template.getMinimumCpus() != null && template.getMinimumCpus() > 0) {
            jobDesc.setMinimumCpus(template.getMinimumCpus());
        }
        if (template.getMaximumCpus() != null && template.getMaximumCpus() > 0) {
            jobDesc.setMaximumCpus(template.getMaximumCpus());
        }
        if (template.getSocketsPerNode() != null && template.getSocketsPerNode() > 0) {
            jobDesc.setSocketsPerNode(template.getSocketsPerNode());
        }
        if (template.getThreadsPerCore() != null && template.getThreadsPerCore() > 0) {
            jobDesc.setThreadsPerCore(template.getThreadsPerCore());
        }
        if (template.getTasksPerNode() != null && template.getTasksPerNode() > 0) {
            jobDesc.setTasksPerNode(template.getTasksPerNode());
        }
        if (template.getTasksPerSocket() != null && template.getTasksPerSocket() > 0) {
            jobDesc.setTasksPerSocket(template.getTasksPerSocket());
        }
        if (template.getTasksPerCore() != null && template.getTasksPerCore() > 0) {
            jobDesc.setTasksPerCore(template.getTasksPerCore());
        }
        if (template.getTasksPerBoard() != null && template.getTasksPerBoard() > 0) {
            jobDesc.setTasksPerBoard(template.getTasksPerBoard());
        }
        if (template.getNtasksPerTres() != null && template.getNtasksPerTres() > 0) {
            jobDesc.setNtasksPerTres(template.getNtasksPerTres());
        }
        if (template.getMinimumCpusPerNode() != null && template.getMinimumCpusPerNode() > 0) {
            jobDesc.setMinimumCpusPerNode(template.getMinimumCpusPerNode());
        }
        if (template.getMinimumBoardsPerNode() != null && template.getMinimumBoardsPerNode() > 0) {
            jobDesc.setMinimumBoardsPerNode(template.getMinimumBoardsPerNode());
        }
        if (template.getMinimumSocketsPerBoard() != null && template.getMinimumSocketsPerBoard() > 0) {
            jobDesc.setMinimumSocketsPerBoard(template.getMinimumSocketsPerBoard());
        }
        if (template.getTemporaryDiskPerNode() != null && template.getTemporaryDiskPerNode() > 0) {
            jobDesc.setTemporaryDiskPerNode(template.getTemporaryDiskPerNode());
        }
        if (template.getMemoryPerCpu() != null && template.getMemoryPerCpu() > 0) {
            jobDesc.setMemoryPerCpu(uint64(template.getMemoryPerCpu()));
        }

        // Build environment variables
        List<String> environment = buildEnvironment();
        if (!environment.isEmpty()) {
            jobDesc.setEnvironment(environment);
        }

        // Generate the batch script (unless template supplies a custom script)
        String script;
        if (template.getScript() != null && !template.getScript().trim().isEmpty()) {
            script = template.getScript();
        } else {
            validateLaunchConfiguration();
            script = generateBatchScript();
        }
        jobDesc.setScript(script);

        LOGGER.info("Built Slurm job: " + jobDesc.getName() + " (partition="
                + jobDesc.getPartition() + ", cpus="
                + jobDesc.getCpusPerTask() + ", memory="
                + jobDesc.getMemoryPerNode() + "MB)");

        return jobDesc;
    }

    private static void applyOptionalString(java.util.function.Consumer<String> setter, String value) {
        if (value != null && !value.trim().isEmpty()) {
            setter.accept(value);
        }
    }

    private static Uint32NoValStruct uint32(int value) {
        Uint32NoValStruct struct = new Uint32NoValStruct();
        struct.setSet(true);
        struct.setInfinite(false);
        struct.setNumber(value);
        return struct;
    }

    private static Uint64NoValStruct uint64(long value) {
        Uint64NoValStruct struct = new Uint64NoValStruct();
        struct.setSet(true);
        struct.setInfinite(false);
        struct.setNumber(value);
        return struct;
    }

    /**
     * Builds the environment variables for the job.
     * Ensures our required environment variables (PATH, LD_LIBRARY_PATH) are always present,
     * while also including any additional variables specified by the user in the template.
     *
     * @return List of environment variable strings in "KEY=value" format
     */
    private List<String> buildEnvironment() {
        List<String> env = new ArrayList<>();

        // REQUIRED: These environment variables are always set for job submission through REST
        env.add("PATH=/usr/local/bin:/usr/bin:/bin");
        env.add("LD_LIBRARY_PATH=/usr/local/lib:/usr/lib");

        // Add user-specified environment variables from template (if any)
        if (template.getEnvironment() != null
                && !template.getEnvironment().trim().isEmpty()) {
            try {
                // Parse the JSON array string from template
                String envJson = template.getEnvironment().trim();

                // Simple parsing for JSON array format: ["VAR1=value1", "VAR2=value2"]
                if (envJson.startsWith("[") && envJson.endsWith("]")) {
                    String content = envJson.substring(1, envJson.length() - 1);

                    // Split by comma, handling quoted strings
                    String[] entries = content.split(",");
                    for (String entry : entries) {
                        String trimmed = entry.trim();
                        // Remove surrounding quotes if present
                        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                            trimmed = trimmed.substring(1, trimmed.length() - 1);
                        }

                        if (!trimmed.isEmpty() && trimmed.contains("=")) {
                            // Don't override our required PATH and LD_LIBRARY_PATH
                            String varName = trimmed.substring(0, trimmed.indexOf("="));
                            if (!varName.equals("PATH") && !varName.equals("LD_LIBRARY_PATH")) {
                                env.add(trimmed);
                                LOGGER.fine("Added user environment variable: " + varName);
                            } else {
                                LOGGER.warning("Ignoring user override of required variable: " + varName);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to parse environment variables from template: " + e.getMessage());
            }
        }

        return env;
    }

    /**
     * Validates that the template can generate an agent launcher script.
     */
    void validateLaunchConfiguration() {
        PyxisConfig pyxis = template.getPyxis();
        if (pyxis != null && pyxis.isConfigured()) {
            return;
        }
        VmocsConfig vmocs = template.getVmocs();
        if (vmocs != null && vmocs.isConfigured()) {
            return;
        }
        AgentLaunchConfig agent = getEffectiveAgent();
        if (agent != null && agent.isConfigured()) {
            agent.validateNativeLaunch();
            return;
        }
        throw new IllegalStateException(
                "Agent launch is not configured. Enable Pyxis container support, enable vmocs VM support, "
                        + "configure native Agent Launch on the cloud or template (jarPath or downloadJar), "
                        + "or supply a custom batch script.");
    }

    @CheckForNull
    private AgentLaunchConfig getEffectiveAgent() {
        return AgentLaunchConfig.merge(cloudAgent, template.getAgent());
    }

    /**
     * Generates the batch script for running the Jenkins inbound agent.
     *
     * <p>Three launch modes are supported (priority order: Pyxis > vmocs > native):
     * <ol>
     *   <li><b>Pyxis</b> — wraps {@code srun} with {@code --container-*} flags; agent runs inside
     *       the container on the compute node.</li>
     *   <li><b>vmocs</b> — launches a QEMU/KVM VM via {@code vmocs launch}, waits for SSH
     *       readiness, then starts the Jenkins agent inside the VM via SSH.</li>
     *   <li><b>Native</b> — agent runs directly on the compute node via {@link AgentLaunchConfig}.</li>
     * </ol>
     *
     * <p>IMPORTANT: For Pyxis and native modes the Jenkins agent always runs on exactly 1 node
     * with 1 task ({@code srun -N1 -n1}), regardless of the user's resource request.
     *
     * @return The complete batch script content
     */
    private String generateBatchScript() {
        StringBuilder script = new StringBuilder();

        script.append("#!/bin/bash\n");
        script.append("set -euo pipefail\n");

        if (template.getMinimumNodes() != null && template.getMinimumNodes() > 1) {
            LOGGER.info(String.format(
                    "Multi-node job allocation: %d nodes requested. "
                            + "Jenkins agent will run on head node (srun -N1 -n1). "
                            + "User's pipeline commands can access all %d nodes via srun.",
                    template.getMinimumNodes(), template.getMinimumNodes()));
        }

        PyxisConfig pyxis = template.getPyxis();
        VmocsConfig vmocs = template.getVmocs();
        boolean useContainer = pyxis != null && pyxis.isConfigured();
        boolean useVm = !useContainer && vmocs != null && vmocs.isConfigured();
        AgentLaunchConfig agent = getEffectiveAgent();

        if (useVm) {
            LOGGER.fine("Using vmocs VM configuration: vm-image=" + vmocs.getVmImage());
            return generateVmocsBatchScript(vmocs);
        }

        if (!useContainer
                && agent != null
                && agent.getSetupScript() != null
                && !agent.getSetupScript().trim().isEmpty()) {
            for (String line : agent.getSetupScript().split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    script.append(trimmed).append("\n");
                }
            }
        }

        String workdir = template.getCurrentWorkingDirectory();
        if (workdir == null || workdir.trim().isEmpty()) {
            workdir = "/tmp/jenkins";
        }
        script.append("mkdir -p ").append(shellQuote(workdir)).append("\n");

        String javaPath;
        String jarReference;

        if (useContainer) {
            javaPath = AgentLaunchConfig.CONTAINER_JAVA_PATH;
            jarReference = AgentLaunchConfig.CONTAINER_JAR_PATH;
        } else if (agent != null && agent.getDownloadJar()) {
            javaPath = agent.getJavaPath();
            script.append("AGENT_JAR=")
                    .append(shellQuote(workdir + "/agent.jar"))
                    .append("\n");
            script.append("if [ ! -f \"$AGENT_JAR\" ]; then\n");
            script.append("  echo 'Downloading Jenkins agent JAR...'\n");
            script.append("  if command -v curl >/dev/null 2>&1; then\n");
            script.append("    curl -fsSL -o \"$AGENT_JAR\" \"")
                    .append(escapeForDoubleQuotedShell(jenkinsUrl))
                    .append("jnlpJars/agent.jar\"\n");
            script.append("  elif command -v wget >/dev/null 2>&1; then\n");
            script.append("    wget -q -O \"$AGENT_JAR\" \"")
                    .append(escapeForDoubleQuotedShell(jenkinsUrl))
                    .append("jnlpJars/agent.jar\"\n");
            script.append("  else\n");
            script.append("    echo 'ERROR: curl or wget required to download agent.jar' >&2\n");
            script.append("    exit 1\n");
            script.append("  fi\n");
            script.append("fi\n");
            jarReference = "\"$AGENT_JAR\"";
        } else {
            javaPath = agent != null ? agent.getJavaPath() : AgentLaunchConfig.DEFAULT_JAVA_PATH;
            jarReference = shellQuote(agent != null ? agent.getJarPath() : "");
        }

        script.append("srun -N1 -n1");

        if (useContainer) {
            appendPyxisFlags(script, pyxis);
            LOGGER.fine("Using Pyxis container configuration: " + pyxis.getContainerImage());
        } else {
            LOGGER.fine("Using native agent launch: java=" + javaPath + ", jar=" + jarReference);
        }

        script.append(" ").append(javaPath).append(" -jar ").append(jarReference);
        script.append(" -url ").append(jenkinsUrl);

        if (agentSecret != null && !agentSecret.isEmpty()) {
            script.append(" -secret ").append(agentSecret);
        }

        script.append(" -name ").append(agentName);
        script.append(" -webSocket");
        script.append(" -workDir /tmp/").append(agentName);
        script.append("\n");

        LOGGER.fine("Generated batch script for agent " + agentName + ":\n" + script);

        return script.toString();
    }

    /**
     * Generates the batch script for vmocs VM-based launch mode.
     *
     * <p>The script:
     * <ol>
     *   <li>Runs {@code vmocs launch} in the background, capturing its output to a temp file.</li>
     *   <li>Waits for vmocs to print the SSH connection string ({@code ssh user@host -p PORT})
     *       once the VM is ready.</li>
     *   <li>Parses the SSH user, host, and port from the connection string.</li>
     *   <li>If {@link VmocsConfig#getAgentJarPath()} is empty, downloads {@code agent.jar} from
     *       the Jenkins controller and copies it into the VM via {@code scp}.</li>
     *   <li>Starts the Jenkins inbound agent inside the VM via SSH (blocks until build ends).</li>
     *   <li>Waits for vmocs to perform graceful VM shutdown when Slurm sends SIGTERM.</li>
     * </ol>
     */
    /**
     * Generates the batch script for vmocs VM-based launch mode.
     *
     * <p>The vmocs Slurm SPANK plugin registers {@code --vm-image=} as a native
     * {@code sbatch} argument. When Slurm runs the job the SPANK plugin (not this script)
     * starts the VM on the compute node. This script's only responsibilities are:
     * <ol>
     *   <li>Declare {@code #SBATCH --vm-image=<image>} in the header so Slurm/SPANK knows
     *       which image to boot.</li>
     *   <li>Poll {@code localhost:<sshPort>} until the VM's SSH service is reachable.
     *       The SPANK plugin prints {@code "VM ready ... ssh -i <key> -p <port> <user>@127.0.0.1"}
     *       to stdout once the VM is up.</li>
     *   <li>If {@link VmocsConfig#getAgentJarPath()} is empty, download {@code agent.jar}
     *       from the Jenkins controller and copy it into the VM via {@code scp}.</li>
     *   <li>Start the Jenkins inbound agent inside the VM via SSH (blocks until build ends).</li>
     *   <li>Exit — the Slurm job ends and the SPANK plugin shuts the VM down.</li>
     * </ol>
     *
     * <p>Resource allocation (CPUs, memory, GPUs, partition, account) is set by the
     * surrounding {@link SlurmJobTemplate} fields; vmocs only contributes the image name
     * and SSH connection parameters.
     */
    private String generateVmocsBatchScript(VmocsConfig vmocs) {
        StringBuilder s = new StringBuilder();

        // #SBATCH --vm-image= must appear in the script header so the vmocs SPANK plugin
        // picks it up at job submission time.
        s.append("#!/bin/bash\n");
        s.append("#SBATCH --vm-image=").append(vmocs.getVmImage()).append("\n");
        s.append("set -euo pipefail\n\n");

        int sshPort = vmocs.getSshPort();
        String sshUser = vmocs.getSshUser();
        int timeoutSec = vmocs.getVmBootTimeoutSec();

        // Build a reusable SSH options string (shared by ssh and scp below).
        s.append("SSH_OPTS=\"-q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null");
        s.append(" -o ConnectTimeout=10");
        if (vmocs.getSshKeyPath() != null && !vmocs.getSshKeyPath().trim().isEmpty()) {
            s.append(" -i ").append(shellQuote(vmocs.getSshKeyPath()));
        }
        s.append("\"\n\n");

        // Poll until the VM's SSH port is reachable on localhost.  The vmocs SPANK plugin
        // starts the VM and prints "VM ready ... ssh -i <key> -p <port> <user>@127.0.0.1"
        // to the job output once ready; we detect readiness by checking the port directly.
        s.append("# Wait for the vmocs SPANK plugin to boot the VM and open SSH port ")
                .append(sshPort)
                .append("\n");
        s.append("TIMEOUT=").append(timeoutSec).append("\n");
        s.append("ELAPSED=0\n");
        s.append("until nc -z 127.0.0.1 ").append(sshPort).append(" 2>/dev/null; do\n");
        s.append("  if [ \"$ELAPSED\" -ge \"$TIMEOUT\" ]; then\n");
        s.append("    echo \"[vmocs] ERROR: VM SSH port ")
                .append(sshPort)
                .append(" not reachable within ${TIMEOUT}s\" >&2\n");
        s.append("    exit 1\n");
        s.append("  fi\n");
        s.append("  sleep 5\n");
        s.append("  ELAPSED=$((ELAPSED + 5))\n");
        s.append("done\n");
        s.append("echo '[vmocs] VM SSH port ").append(sshPort).append(" is ready'\n\n");

        // Determine agent.jar location inside the VM.
        String preinstalledJar = vmocs.getAgentJarPath();
        boolean hasPreinstalledJar =
                preinstalledJar != null && !preinstalledJar.trim().isEmpty();

        if (hasPreinstalledJar) {
            s.append("# Use pre-installed agent.jar inside the VM image\n");
            s.append("AGENT_JAR_VM=").append(shellQuote(preinstalledJar)).append("\n\n");
        } else {
            // Download agent.jar from the Jenkins controller onto the compute node,
            // then scp it into the VM (vm port is forwarded to localhost).
            s.append("# Download agent.jar from Jenkins controller and copy into VM via scp\n");
            s.append("HOST_AGENT_JAR=$(mktemp /tmp/agent-XXXXXX.jar)\n");
            s.append("trap \"rm -f \\\"$HOST_AGENT_JAR\\\"\" EXIT\n");
            s.append("if command -v curl >/dev/null 2>&1; then\n");
            s.append("  curl -fsSL -o \"$HOST_AGENT_JAR\" \"")
                    .append(escapeForDoubleQuotedShell(jenkinsUrl))
                    .append("jnlpJars/agent.jar\"\n");
            s.append("elif command -v wget >/dev/null 2>&1; then\n");
            s.append("  wget -q -O \"$HOST_AGENT_JAR\" \"")
                    .append(escapeForDoubleQuotedShell(jenkinsUrl))
                    .append("jnlpJars/agent.jar\"\n");
            s.append("else\n");
            s.append("  echo '[vmocs] ERROR: curl or wget required to download agent.jar' >&2\n");
            s.append("  exit 1\n");
            s.append("fi\n");
            // scp uses the same SSH options; -P (uppercase) sets the port for scp.
            s.append("scp $SSH_OPTS -P ").append(sshPort).append(" \"$HOST_AGENT_JAR\" ");
            s.append(shellQuote(sshUser + "@127.0.0.1")).append(":~/agent.jar\n");
            s.append("AGENT_JAR_VM='~/agent.jar'\n\n");
        }

        // Start the Jenkins inbound agent inside the VM via SSH.
        // This call blocks for the duration of the build.  When it returns the batch script
        // exits, the Slurm job terminates, and the SPANK plugin shuts the VM down.
        s.append("# Start Jenkins inbound agent inside VM (blocks until build completes)\n");
        s.append("echo '[vmocs] Starting Jenkins agent inside VM...'\n");
        s.append("ssh $SSH_OPTS -p ").append(sshPort).append(" ");
        s.append(shellQuote(sshUser + "@127.0.0.1")).append(" \\\n");
        s.append("  \"java -jar \\\"$AGENT_JAR_VM\\\"");
        s.append(" -url ").append(shellEscapeDoubleQuoted(jenkinsUrl));
        if (agentSecret != null && !agentSecret.isEmpty()) {
            s.append(" -secret ").append(shellEscapeDoubleQuoted(agentSecret));
        }
        s.append(" -name ").append(shellEscapeDoubleQuoted(agentName));
        s.append(" -webSocket");
        s.append(" -workDir /tmp/").append(shellEscapeDoubleQuoted(agentName));
        s.append("\"\n\n");

        s.append("# Script exits here → Slurm job ends → SPANK plugin shuts down the VM\n");

        LOGGER.fine("Generated vmocs batch script for agent " + agentName + ":\n" + s);
        return s.toString();
    }

    private static String shellEscapeDoubleQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
    }

    private static void appendPyxisFlags(StringBuilder script, PyxisConfig pyxis) {
        if (pyxis.getContainerImage() != null
                && !pyxis.getContainerImage().trim().isEmpty()) {
            script.append(" --container-image=").append(pyxis.getContainerImage());
        }
        if (pyxis.getContainerMountHome()) {
            script.append(" --container-mount-home");
        }
        if (pyxis.getContainerMounts() != null
                && !pyxis.getContainerMounts().trim().isEmpty()) {
            script.append(" --container-mounts=").append(pyxis.getContainerMounts());
        }
        if (pyxis.getContainerWorkdir() != null
                && !pyxis.getContainerWorkdir().trim().isEmpty()) {
            script.append(" --container-workdir=").append(pyxis.getContainerWorkdir());
        }
        if (pyxis.getContainerName() != null && !pyxis.getContainerName().trim().isEmpty()) {
            script.append(" --container-name=").append(pyxis.getContainerName());
        }
        if (pyxis.getContainerWritable()) {
            script.append(" --container-writable");
        }
        if (pyxis.getContainerRemap()) {
            script.append(" --container-remap-root");
        }
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String escapeForDoubleQuotedShell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Validates that the job description is ready for submission.
     *
     * @param jobDesc The job description to validate
     * @throws IllegalStateException if validation fails
     */
    public static void validate(@NonNull JobDescMsg jobDesc) throws IllegalStateException {
        if (jobDesc.getName() == null || jobDesc.getName().isEmpty()) {
            throw new IllegalStateException("Job name is required");
        }

        if (jobDesc.getScript() == null || jobDesc.getScript().isEmpty()) {
            throw new IllegalStateException("Job script is required");
        }

        // Warn about common configuration issues
        Uint64NoValStruct memory = jobDesc.getMemoryPerNode();
        if (memory != null
                && Boolean.TRUE.equals(memory.getSet())
                && memory.getNumber() != null
                && memory.getNumber() < 512) {
            LOGGER.warning("Job " + jobDesc.getName() + " has very low memory: " + memory.getNumber() + "MB");
        }

        Uint32NoValStruct time = jobDesc.getTimeLimit();
        if (time != null && Boolean.TRUE.equals(time.getSet()) && time.getNumber() != null && time.getNumber() < 10) {
            LOGGER.warning("Job " + jobDesc.getName() + " has very short time limit: " + time.getNumber() + " minutes");
        }
    }
}
