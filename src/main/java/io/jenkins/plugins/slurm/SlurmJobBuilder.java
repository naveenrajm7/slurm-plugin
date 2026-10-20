package io.jenkins.plugins.slurm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.slurm.client.model.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private static final ObjectMapper ENVIRONMENT_MAPPER = new ObjectMapper();

    private static final TypeReference<List<String>> ENVIRONMENT_LIST = new TypeReference<List<String>>() {};

    /**
     * JDK 11+ single-file source used inside the guest when curl and wget are absent. The guest
     * runs it with {@code java JenkinsAgentJarDownloader.java}.
     */
    private static final String GUEST_JAR_DOWNLOADER_SOURCE = ""
            + "import java.net.URI;\n"
            + "import java.net.http.HttpClient;\n"
            + "import java.net.http.HttpRequest;\n"
            + "import java.net.http.HttpResponse;\n"
            + "import java.nio.file.Path;\n"
            + "public class JenkinsAgentJarDownloader {\n"
            + "    public static void main(String[] args) throws Exception {\n"
            + "        if (args.length != 2) {\n"
            + "            throw new IllegalArgumentException(\"usage: JenkinsAgentJarDownloader URL DESTINATION\");\n"
            + "        }\n"
            + "        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();\n"
            + "        HttpRequest request = HttpRequest.newBuilder(URI.create(args[0])).GET().build();\n"
            + "        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(Path.of(args[1])));\n"
            + "        if (response.statusCode() != 200) {\n"
            + "            throw new IllegalStateException(\"Jenkins agent JAR download failed with HTTP status \" + response.statusCode());\n"
            + "        }\n"
            + "    }\n"
            + "}\n";

    private final SlurmJobTemplate template;
    private final String agentName;
    private final String jenkinsUrl;
    private final String agentSecret;
    private final SlurmCloud cloud;
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
        this(template, agentName, jenkinsUrl, agentSecret, (AgentLaunchConfig) null);
    }

    /**
     * Creates a new job builder with cloud-level native agent launch defaults.
     */
    public SlurmJobBuilder(
            @NonNull SlurmJobTemplate template,
            @NonNull String agentName,
            @NonNull String jenkinsUrl,
            @CheckForNull String agentSecret,
            @CheckForNull AgentLaunchConfig cloudAgent) {
        this(template, agentName, jenkinsUrl, agentSecret, null, cloudAgent);
    }

    /**
     * Creates a new job builder that inherits the cloud's launch mode and settings when the
     * template has not chosen its own.
     */
    public SlurmJobBuilder(
            @NonNull SlurmJobTemplate template,
            @NonNull String agentName,
            @NonNull String jenkinsUrl,
            @CheckForNull String agentSecret,
            @CheckForNull SlurmCloud cloud) {
        this(template, agentName, jenkinsUrl, agentSecret, cloud, cloud != null ? cloud.getAgent() : null);
    }

    private SlurmJobBuilder(
            @NonNull SlurmJobTemplate template,
            @NonNull String agentName,
            @NonNull String jenkinsUrl,
            @CheckForNull String agentSecret,
            @CheckForNull SlurmCloud cloud,
            @CheckForNull AgentLaunchConfig cloudAgent) {
        this.template = template;
        this.agentName = agentName;
        this.jenkinsUrl = jenkinsUrl;
        this.agentSecret = agentSecret;
        this.cloud = cloud;
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
        boolean vmocs = "VMOCS".equals(mode());

        // Add user-specified environment variables from template (if any).
        // The template stores a JSON array of "KEY=value" strings. Values may contain
        // commas, spaces, and quotes, so this must be parsed as JSON rather than split on ','.
        String envJson = template.getEnvironment();
        if (envJson != null && !envJson.trim().isEmpty()) {
            try {
                List<String> entries = ENVIRONMENT_MAPPER.readValue(envJson.trim(), ENVIRONMENT_LIST);
                if (entries != null) {
                    for (String entry : entries) {
                        appendUserEnvironment(env, entry, vmocs);
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to parse environment variables from template: " + e.getMessage());
            }
        }

        if (vmocs) {
            env.add("JENKINS_URL=" + withTrailingSlash(jenkinsUrl));
            env.add("JENKINS_SECRET=" + (agentSecret != null ? agentSecret : ""));
            env.add("JENKINS_AGENT_NAME=" + agentName);
        }

        return env;
    }

    private static String withTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.endsWith("/") ? url : url + "/";
    }

    /**
     * Appends one user {@code KEY=value} entry. Entries that do not contain {@code '='}
     * are ignored. User values are not allowed to replace the required {@code PATH} and
     * {@code LD_LIBRARY_PATH} variables.
     */
    private static void appendUserEnvironment(List<String> env, String entry, boolean protectJenkinsVariables) {
        if (entry == null) {
            return;
        }
        String trimmed = entry.trim();
        if (trimmed.isEmpty() || !trimmed.contains("=")) {
            return;
        }
        String varName = trimmed.substring(0, trimmed.indexOf('='));
        boolean protectedJenkinsVariable = protectJenkinsVariables
                && ("JENKINS_URL".equals(varName)
                        || "JENKINS_SECRET".equals(varName)
                        || "JENKINS_AGENT_NAME".equals(varName));
        if (!varName.equals("PATH") && !varName.equals("LD_LIBRARY_PATH") && !protectedJenkinsVariable) {
            env.add(trimmed);
            LOGGER.fine("Added user environment variable: " + varName);
        } else {
            LOGGER.warning("Ignoring user override of required variable: " + varName);
        }
    }

    /**
     * Validates that the template can generate an agent launcher script.
     */
    void validateLaunchConfiguration() {
        String mode = mode();
        if ("PYXIS".equals(mode)) {
            PyxisConfig pyxis = effectivePyxis();
            if (pyxis == null || !pyxis.isConfigured()) {
                throw new IllegalStateException("Pyxis launch mode is selected but no container image is configured. "
                        + "Set a container image in the Pyxis configuration, or switch "
                        + "to Native or VMoCS launch mode.");
            }
            return;
        }
        if ("VMOCS".equals(mode)) {
            VmocsConfig vmocs = effectiveVmocs();
            if (vmocs == null || !vmocs.isConfigured()) {
                throw new IllegalStateException("VMoCS launch mode is selected but no VM template is configured. "
                        + "Set a VMoCS template name, or switch to Native or Pyxis launch mode.");
            }
            try {
                vmocs.validate();
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            return;
        }
        // NATIVE mode. A cloud-level native default applies only in this mode.
        AgentLaunchConfig agent = getEffectiveAgent();
        if (agent != null && agent.isConfigured()) {
            agent.validateNativeLaunch();
            return;
        }
        throw new IllegalStateException("Native launch mode is selected but agent launch is not configured. "
                + "Enable Pyxis or VMoCS launch, configure native "
                + "Agent Launch on the cloud or template (jarPath or downloadJar), "
                + "or supply a custom batch script.");
    }

    @CheckForNull
    private AgentLaunchConfig getEffectiveAgent() {
        if (cloud == null) {
            return AgentLaunchConfig.merge(cloudAgent, template.getAgent());
        }
        return LaunchDefaults.effectiveAgent(cloud, template);
    }

    @NonNull
    private String mode() {
        return LaunchDefaults.effectiveMode(cloud, template);
    }

    @CheckForNull
    private PyxisConfig effectivePyxis() {
        return LaunchDefaults.effectivePyxis(cloud, template);
    }

    @CheckForNull
    private VmocsConfig effectiveVmocs() {
        return LaunchDefaults.effectiveVmocs(cloud, template);
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
        if ("VMOCS".equals(mode())) {
            return generateVmocsBatchScript();
        }

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

        PyxisConfig pyxis = effectivePyxis();
        boolean useContainer = "PYXIS".equals(mode()) && pyxis != null && pyxis.isConfigured();
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
        if (!useContainer) {
            appendNativeAgentRoot(script, workdir);
        }

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
        if (useContainer) {
            // Inside the container this path is private to the job. On the host, native
            // launch uses JENKINS_AGENT_ROOT instead so build workspaces do not collide.
            script.append(" -workDir /tmp/").append(agentName);
        } else {
            script.append(" -workDir \"$JENKINS_AGENT_ROOT\"");
        }
        script.append("\n");

        LOGGER.fine("Generated batch script for agent " + agentName + ":\n" + script);

        return script.toString();
    }

    /**
     * Host batch script that asks VMoCS to boot a guest and run the Jenkins agent there.
     * The secret and controller URL are job environment values, not script text.
     */
    private String generateVmocsBatchScript() {
        VmocsConfig vmocs = effectiveVmocs();
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -euo pipefail\n");

        String workdir = template.getCurrentWorkingDirectory();
        if (workdir == null || workdir.trim().isEmpty()) {
            workdir = "/tmp/jenkins";
        }
        script.append("mkdir -p ").append(shellQuote(workdir)).append('\n');

        int cpus = template.getCpusPerTask() == null || template.getCpusPerTask() < 1
                ? 1
                : template.getCpusPerTask();
        List<String> args = new ArrayList<>();
        args.add("srun");
        args.add("-N1");
        args.add("-n1");
        args.add("--cpus-per-task=" + cpus);
        args.add("--kill-on-bad-exit=1");
        args.add("--export=ALL");
        args.add("--vm-image=" + vmocs.getVmImage().trim());
        for (String name : forwardedVmocsVariables()) {
            args.add("--vm-forward-env=" + name);
        }
        args.add("/bin/bash");
        args.add("-lc");
        args.add(vmocsGuestCommand(vmocs));

        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                script.append(' ');
            }
            script.append(shellQuote(args.get(i)));
        }
        script.append('\n');
        LOGGER.fine("Generated VMoCS batch script for agent " + agentName);
        return script.toString();
    }

    private List<String> forwardedVmocsVariables() {
        List<String> names = new ArrayList<>(VmocsConfig.REQUIRED_FORWARDED_VARIABLES);
        Set<String> supplied = new LinkedHashSet<>();
        for (String entry : buildEnvironment()) {
            int separator = entry.indexOf('=');
            if (separator > 0) {
                supplied.add(entry.substring(0, separator));
            }
        }
        VmocsConfig vmocs = effectiveVmocs();
        if (vmocs != null) {
            for (String name : vmocs.customForwardEnvironmentNames()) {
                if (supplied.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static String vmocsGuestCommand(VmocsConfig vmocs) {
        StringBuilder guest = new StringBuilder();
        guest.append("set -euo pipefail\n");
        guest.append("umask 077\n");
        guest.append("work=").append(shellQuote(vmocs.getWorkDir())).append('\n');
        guest.append("mkdir -p \"$work\"\n");
        guest.append("java_bin=").append(shellQuote(vmocs.getJavaPath())).append('\n');
        if (vmocs.hasPreinstalledJar()) {
            guest.append("agent_jar=").append(shellQuote(vmocs.getJarPath().trim())).append('\n');
        } else {
            guest.append("agent_jar=\"$work/agent.jar\"\n");
            guest.append("if [ ! -f \"$agent_jar\" ]; then\n");
            guest.append("  url=\"${JENKINS_URL}jnlpJars/agent.jar\"\n");
            guest.append("  if command -v curl >/dev/null 2>&1; then\n");
            guest.append("    curl -fsSL -o \"$agent_jar\" \"$url\"\n");
            guest.append("  elif command -v wget >/dev/null 2>&1; then\n");
            guest.append("    wget -q -O \"$agent_jar\" \"$url\"\n");
            guest.append("  else\n");
            guest.append("    cat > \"$work/JenkinsAgentJarDownloader.java\" << 'JAVA_END'\n");
            guest.append(GUEST_JAR_DOWNLOADER_SOURCE);
            guest.append("JAVA_END\n");
            guest.append("    \"$java_bin\" \"$work/JenkinsAgentJarDownloader.java\" \"$url\" \"$agent_jar\"\n");
            guest.append("  fi\n");
            guest.append("fi\n");
        }
        guest.append("exec \"$java_bin\" -jar \"$agent_jar\"");
        guest.append(" -url \"$JENKINS_URL\"");
        guest.append(" -secret \"$JENKINS_SECRET\"");
        guest.append(" -name \"$JENKINS_AGENT_NAME\"");
        guest.append(" -webSocket -workDir \"$work\"\n");
        return guest.toString();
    }

    /**
     * Jenkins remote root for the agent that this template will launch.
     *
     * <p>Native generated launchers use a private subdirectory of the Slurm working directory.
     * Generated VMoCS launchers use the guest work directory. Pyxis and custom scripts keep the
     * template working directory.
     */
    @NonNull
    public static String remoteFilesystem(@NonNull SlurmJobTemplate template, @NonNull String agentName) {
        return remoteFilesystem(null, template, agentName);
    }

    /**
     * Jenkins remote root, using the cloud launch default when the template inherits it.
     */
    @NonNull
    public static String remoteFilesystem(
            @CheckForNull SlurmCloud cloud, @NonNull SlurmJobTemplate template, @NonNull String agentName) {
        if (usesPerAgentWorkspace(cloud, template)) {
            return nativeAgentRoot(template.getCurrentWorkingDirectory(), agentName);
        }
        if ("VMOCS".equals(LaunchDefaults.effectiveMode(cloud, template)) && !hasCustomScript(template)) {
            VmocsConfig vmocs = LaunchDefaults.effectiveVmocs(cloud, template);
            if (vmocs != null) {
                return vmocs.getWorkDir();
            }
        }
        String workingDirectory = template.getCurrentWorkingDirectory();
        if (workingDirectory == null || workingDirectory.trim().isEmpty()) {
            return "/tmp/jenkins";
        }
        return workingDirectory;
    }

    private static boolean hasCustomScript(SlurmJobTemplate template) {
        String customScript = template.getScript();
        return customScript != null && !customScript.trim().isEmpty();
    }

    /**
     * Jenkins remote root for one native agent.
     *
     * <p>Slurm {@code current_working_directory} stays the template working directory, which
     * already exists on the node. This subdirectory is created by the batch script, so the
     * cluster does not need a directory prepared for every agent. Build workspaces land here
     * ({@code remoteFS}), not in the shared parent, so a wipe cannot delete another agent's checkout.
     *
     * @param workingDirectory template working directory; blank uses {@code /tmp/jenkins}
     * @param agentName unique Jenkins agent name
     */
    @NonNull
    public static String nativeAgentRoot(@CheckForNull String workingDirectory, @NonNull String agentName) {
        String parent = workingDirectory == null ? "" : workingDirectory.trim();
        if (parent.isEmpty()) {
            parent = "/tmp/jenkins";
        }
        while (parent.length() > 1 && parent.endsWith("/")) {
            parent = parent.substring(0, parent.length() - 1);
        }
        return parent + "/agents/" + agentName;
    }

    /**
     * Whether this template's generated native launcher isolates the Jenkins workspace per agent.
     *
     * <p>Pyxis jobs keep the container filesystem. A custom batch script is used as-is, so the
     * plugin cannot create or clean a private directory for it.
     */
    public static boolean usesPerAgentWorkspace(@NonNull SlurmJobTemplate template) {
        return usesPerAgentWorkspace(null, template);
    }

    public static boolean usesPerAgentWorkspace(
            @CheckForNull SlurmCloud cloud, @NonNull SlurmJobTemplate template) {
        if (!"NATIVE".equals(LaunchDefaults.effectiveMode(cloud, template))) {
            return false;
        }
        String customScript = template.getScript();
        return customScript == null || customScript.trim().isEmpty();
    }

    /**
     * Creates {@code $JENKINS_AGENT_ROOT} and removes it when the batch script exits.
     *
     * <p>{@code TERM} and {@code INT} call {@code exit} so the {@code EXIT} trap still runs when
     * Slurm cancels the job. A signal with no trap would skip {@code EXIT}.
     */
    private void appendNativeAgentRoot(StringBuilder script, String workdir) {
        script.append("JENKINS_AGENT_ROOT=")
                .append(shellQuote(nativeAgentRoot(workdir, agentName)))
                .append("\n");
        script.append("mkdir -p \"$JENKINS_AGENT_ROOT\"\n");
        script.append("trap 'rm -rf \"$JENKINS_AGENT_ROOT\"' EXIT\n");
        script.append("trap 'exit 143' TERM\n");
        script.append("trap 'exit 130' INT\n");
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
