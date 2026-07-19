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
        String mode = template.getLaunchMode();
        if ("PYXIS".equals(mode)) {
            PyxisConfig pyxis = template.getPyxis();
            if (pyxis == null || !pyxis.isConfigured()) {
                throw new IllegalStateException("Pyxis launch mode is selected but no container image is configured. "
                        + "Set a container image in the Pyxis configuration, or switch "
                        + "to Native launch mode and configure an agent.jar path.");
            }
            return;
        }
        // NATIVE mode
        AgentLaunchConfig agent = getEffectiveAgent();
        if (agent != null && agent.isConfigured()) {
            agent.validateNativeLaunch();
            return;
        }
        throw new IllegalStateException("Native launch mode is selected but agent launch is not configured. "
                + "Enable Pyxis container support, configure native "
                + "Agent Launch on the cloud or template (jarPath or downloadJar), "
                + "or supply a custom batch script.");
    }

    @CheckForNull
    private AgentLaunchConfig getEffectiveAgent() {
        return AgentLaunchConfig.merge(cloudAgent, template.getAgent());
    }

    /**
     * Generates the batch script for running the Jenkins inbound agent.
     *
     * <p>Uses Pyxis container paths when configured; otherwise uses {@link AgentLaunchConfig}
     * for native (host) launch.
     *
     * <p>IMPORTANT: The Jenkins agent always runs on exactly 1 node with 1 task (-N1 -n1),
     * regardless of the user's resource request.
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
        boolean useContainer = "PYXIS".equals(template.getLaunchMode()) && pyxis != null && pyxis.isConfigured();
        AgentLaunchConfig agent = getEffectiveAgent();

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
