package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.slurm.client.ApiException;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import io.jenkins.plugins.slurm.client.model.JobDescMsg;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/**
 * Launcher for Slurm agents that submits jobs via the Slurm REST API.
 * The agent connects back to Jenkins using JNLP.
 *
 * This launcher follows the Kubernetes plugin pattern:
 * - Submits the job once (no retry at launcher level)
 * - Actively polls job status while waiting for agent connection
 * - Fails fast if job enters a failed state
 * - Times out with clear error message
 * - Lets Jenkins provisioning strategy decide whether to retry
 */
public class SlurmLauncher extends JNLPLauncher {
    private static final Logger LOGGER = Logger.getLogger(SlurmLauncher.class.getName());

    // How often to check job status (5 seconds)
    private static final long STATUS_CHECK_INTERVAL_MS = 5000;

    // How often to log progress (30 seconds)
    private static final long PROGRESS_LOG_INTERVAL_MS = 30000;

    private volatile boolean launched = false;

    /**
     * Provisioning exception if any.
     * Following Kubernetes plugin pattern: stores the first failure to prevent retries.
     */
    @CheckForNull
    private transient Throwable problem;

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {
        LOGGER.info("=== Slurm Launcher: launch() called for computer: " + computer.getName() + " ===");

        if (!(computer instanceof SlurmComputer)) {
            throw new IllegalArgumentException("Computer must be an instance of SlurmComputer");
        }

        SlurmComputer slurmComputer = (SlurmComputer) computer;
        computer.setAcceptingTasks(false); // Kubernetes pattern: disable tasks until ready
        SlurmAgent agent = slurmComputer.getNode();

        if (agent == null) {
            LOGGER.severe("Agent is null for computer: " + computer.getName());
            throw new IllegalStateException("Agent is null");
        }

        TaskListener console = consoleListener(agent, listener);

        // Kubernetes pattern: Check if previous launch failed permanently
        if (problem != null) {
            LOGGER.severe("Launch previously failed permanently for agent: " + agent.getNodeName() + " - "
                    + problem.getMessage());
            console.getLogger().println("[Slurm] Agent launch previously failed: " + problem.getMessage());
            console.error(problem.getMessage());
            listener.error("Agent launch previously failed: " + problem.getMessage());
            listener.error("Not retrying - please check agent configuration and Slurm logs");
            // Don't throw - just return and keep agent offline
            // This prevents the exception from bubbling up and triggering retry logic
            return;
        }

        // Kubernetes pattern: if already launched, just activate and return
        if (launched) {
            LOGGER.info("Agent already launched, activating: " + agent.getNodeName());
            computer.setAcceptingTasks(true);
            return;
        }

        LOGGER.info("Launching Slurm agent: " + agent.getNodeName());
        listener.getLogger().println("Launching Slurm agent: " + agent.getNodeName());

        SlurmCloud cloud = null; // Declare in method scope for catch blocks
        try {
            // Get the cloud
            cloud = agent.getSlurmCloud();
            LOGGER.info("Got cloud: " + cloud.name);

            // Get the template by ID
            SlurmJobTemplate template = cloud.getTemplateById(agent.getTemplateId());
            if (template == null) {
                LOGGER.severe("Template not found with ID: " + agent.getTemplateId());
                throw new IllegalStateException("Template not found with ID: " + agent.getTemplateId());
            }

            LOGGER.info("Using template: " + template.getName());
            listener.getLogger().println("Using template: " + template.getName());

            // Set the launching state
            slurmComputer.setLaunching(true);
            LOGGER.info("Set launching state to true");

            try {
                // Build the job description
                LOGGER.info("Building Slurm job description...");
                SlurmJobBuilder builder = new SlurmJobBuilder(
                        template,
                        agent.getNodeName(),
                        getJenkinsUrl(cloud),
                        getAgentSecret(slurmComputer),
                        cloud.getAgent());
                JobDescMsg jobDesc = builder.build();
                LOGGER.info("Job description built successfully");

                // Submit the job
                LOGGER.info("Submitting job to Slurm...");
                listener.getLogger().println("Submitting job to Slurm...");
                String jobId = cloud.submitJob(jobDesc, console);

                // Store the job ID in the agent
                agent.setSlurmJobId(jobId);
                SlurmCloudStats.attachLaunchingOk(agent, "Submitted Slurm job " + jobId);
                LOGGER.info("Slurm job submitted with ID: " + jobId);
                listener.getLogger().println("Slurm job submitted with ID: " + jobId);

                // Wait for the agent to connect with active status checking
                long agentTimeoutMs = (long) cloud.getAgentTimeoutMinutes() * 60 * 1000;
                LOGGER.info("Waiting for agent to connect via WebSocket/JNLP (timeout: "
                        + cloud.getAgentTimeoutMinutes() + " minutes)...");
                listener.getLogger()
                        .println("Waiting for agent to connect (timeout: " + cloud.getAgentTimeoutMinutes()
                                + " minutes)...");
                waitForAgentConnection(slurmComputer, agent, cloud, template, jobId, agentTimeoutMs, listener);

            } finally {
                slurmComputer.setLaunching(false);
                LOGGER.info("Set launching state to false");
            }

        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Interrupted while launching Slurm agent: " + agent.getNodeName(), e);
            String interruptMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            console.getLogger().println("[Slurm] Agent provisioning interrupted: " + interruptMsg);
            console.error(interruptMsg);
            listener.error("Launch interrupted: " + interruptMsg);

            // Set problem field to prevent retries
            setProblem(e);
            SlurmCloudStats.attachLaunchingFail(agent, e);

            // Cancel the Slurm job if it was submitted
            cancelJobOnFailure(agent, cloud, listener);

            // Kubernetes pattern: Cancel queue item to prevent infinite provisioning loop
            JobUtils.cancelQueueItemFor(agent, "Launch interrupted: " + e.getMessage());

            // Set computer offline
            String errorMsg = "Launch interrupted: " + e.getMessage();
            slurmComputer.setTemporarilyOffline(true, new OfflineCause.UserCause(null, errorMsg));

            // Remove the node from Jenkins
            removeNodeOnFailure(agent);

            Thread.currentThread().interrupt();
            throw new RuntimeException("Launch interrupted", e);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to launch Slurm agent: " + agent.getNodeName(), e);
            String failureMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (agent.getSlurmJobId() != null) {
                console.getLogger().println("[Slurm] Agent provisioning failed: " + failureMsg);
                console.error(failureMsg);
            }
            listener.error("Failed to launch Slurm agent: " + failureMsg);

            // Set problem field to prevent retries
            setProblem(e);
            SlurmCloudStats.attachLaunchingFail(agent, e);

            // Cancel the Slurm job if it was submitted
            cancelJobOnFailure(agent, cloud, listener);

            // Kubernetes pattern: Cancel queue item to prevent infinite provisioning loop
            JobUtils.cancelQueueItemFor(agent, "Launch failed: " + e.getMessage());

            // Set computer offline
            String errorMsg = "Launch failed: " + e.getMessage();
            slurmComputer.setTemporarilyOffline(true, new OfflineCause.UserCause(null, errorMsg));

            // Remove the node from Jenkins
            removeNodeOnFailure(agent);

            throw new RuntimeException("Failed to launch Slurm agent", e);
        }
    }

    /**
     * Cancel the Slurm job when launcher fails.
     */
    private void cancelJobOnFailure(SlurmAgent agent, SlurmCloud cloud, TaskListener listener) {
        String jobId = agent.getSlurmJobId();
        if (jobId != null && cloud != null) {
            try {
                LOGGER.info("Canceling Slurm job " + jobId + " due to launch failure");
                cloud.cancelJob(jobId, listener);
                listener.getLogger().println("Canceled Slurm job: " + jobId);
            } catch (Exception cancelEx) {
                LOGGER.log(Level.WARNING, "Failed to cancel Slurm job " + jobId, cancelEx);
                listener.getLogger()
                        .println("Warning: Failed to cancel Slurm job " + jobId + ": " + cancelEx.getMessage());
            }
        }
    }

    /**
     * Remove the agent node from Jenkins on failure.
     * Kubernetes plugin pattern: use node.terminate() instead of manual removal.
     */
    private void removeNodeOnFailure(SlurmAgent agent) {
        try {
            agent.terminate();
            LOGGER.info("Terminated failed agent node: " + agent.getNodeName());
        } catch (IOException | InterruptedException removeEx) {
            LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
        }
    }

    // -------------------------------------------------------------------------
    // Error reporting helpers (Kubernetes-style: contextual, not banner-heavy)
    // -------------------------------------------------------------------------

    /**
     * Logs a rich, actionable message when the Slurm job itself failed
     * (state is FAILED, CANCELLED, TIMEOUT, NODE_FAIL, etc.).
     */
    private void logProvisioningFailure(
            TaskListener listener, String jobId, String jobState, SlurmJobTemplate template) {
        PrintStream log = listener.getLogger();
        String workDir = template != null ? template.getCurrentWorkingDirectory() : null;

        log.println("");
        log.println("[Slurm] Agent provisioning failed — job " + jobId + " entered state: " + jobState);
        log.println("");

        // State-specific explanation
        switch (jobState) {
            case "FAILED":
                log.println("\tThe job script exited with a non-zero status.");
                log.println("\tThis is often caused by an error in the batch script, a missing");
                log.println("\tcontainer image, or the working directory not existing on the node.");
                break;
            case "CANCELLED":
                log.println("\tThe job was cancelled — either by Slurm policy, by the admin,");
                log.println("\tor the Jenkins cloud cancelled it due to an earlier error.");
                break;
            case "TIMEOUT":
                log.println("\tThe job exceeded its time limit. Consider increasing time_limit");
                log.println("\tin the template, or check why the agent startup took too long.");
                break;
            case "NODE_FAIL":
                log.println("\tThe node the job ran on failed (hardware or OS issue).");
                log.println("\tCheck node health: sinfo -N -l");
                break;
            case "OUT_OF_MEMORY":
                log.println("\tThe job was killed because it exceeded its memory limit.");
                log.println("\tIncrease memory_per_node in the template.");
                break;
            default:
                log.println("\tSee Slurm documentation for state: " + jobState);
                break;
        }

        log.println("");
        log.println("\tLogs to check:");
        logOutputFilePaths(log, jobId, workDir);
        log.println("\t  scontrol show job " + jobId + "  (if job still in Slurm history)");

        if (workDir != null) {
            log.println("");
            log.println("\tNote: output files are written to the working directory configured");
            log.println("\tin the template (" + workDir + "). If that directory does not exist");
            log.println("\ton the compute node, the job will fail silently with no output files.");
        }

        log.println("");
        log.println("\tCommon silent failures:");
        log.println("\t  - Working directory does not exist on compute node → job exits immediately");
        log.println("\t  - Container image not found / pull failure → non-zero exit");
        log.println("\t  - Requested TRES (GPU, etc.) not available on partition");
        log.println("");
    }

    /**
     * Logs a rich, actionable message when the Slurm job COMPLETED successfully
     * but the Jenkins agent never connected back (startup script failed silently).
     */
    private void logAgentConnectionFailure(TaskListener listener, String jobId, SlurmJobTemplate template) {
        PrintStream log = listener.getLogger();
        String workDir = template != null ? template.getCurrentWorkingDirectory() : null;

        log.println("");
        log.println("[Slurm] Agent provisioning failed — job " + jobId
                + " completed but the agent never connected to Jenkins");
        log.println("");
        log.println("\tThe Slurm job exited cleanly (state: COMPLETED) but no JNLP connection");
        log.println("\twas established. The agent startup script ran and exited without error,");
        log.println("\twhich usually means one of these silent failures occurred:");
        log.println("");
        log.println("\t  - Working directory does not exist on compute node");
        log.println("\t    → Slurm silently skips to COMPLETED with exit 0");
        log.println("\t  - Jenkins URL is not reachable from the compute node");
        log.println("\t    → agent.jar download or WebSocket handshake fails");
        log.println("\t  - Java is not on PATH on the compute node");
        log.println("\t    → the launcher line in the batch script fails");
        log.println("\t  - Firewall / proxy blocks the JNLP port (" + getJnlpPort() + ")");
        log.println("");
        log.println("\tLogs to check:");
        logOutputFilePaths(log, jobId, workDir);

        if (workDir != null) {
            log.println("\t  Check that " + workDir + " exists on every compute node");
            log.println("\t  in the target partition.");
        }

        log.println("\t  curl -v " + getJenkinsUrl() + "  (run from a compute node)");
        log.println("\t  which java  (run from a compute node)");
        log.println("");
    }

    /**
     * Logs a rich, actionable message when we timed out waiting for the agent.
     */
    private void logLaunchTimeout(
            TaskListener listener,
            String jobId,
            SlurmJobTemplate template,
            boolean jobReachedRunning,
            long agentTimeoutMs) {
        PrintStream log = listener.getLogger();
        String workDir = template != null ? template.getCurrentWorkingDirectory() : null;

        log.println("");
        log.println("[Slurm] Agent provisioning timed out after " + (agentTimeoutMs / 1000) + "s — job " + jobId);
        log.println("");

        if (!jobReachedRunning) {
            log.println("\tThe Slurm job never reached RUNNING state within the timeout.");
            log.println("\tThis usually means the job is stuck in the queue:");
            log.println("");
            log.println("\t  squeue -j " + jobId + "  (check REASON column)");
            log.println("\t  scontrol show job " + jobId);
            log.println("");
            log.println("\tCommon reasons: insufficient resources, partition limits,");
            log.println("\tQOS/account limits, or no nodes matching the job constraints.");
        } else {
            log.println("\tThe Slurm job was RUNNING but the agent never connected.");
            log.println("\tThis is the same symptom as a silent startup script failure — see:");
            log.println("");
            log.println("\tLogs to check:");
            logOutputFilePaths(log, jobId, workDir);

            if (workDir != null) {
                log.println("\t  Verify " + workDir + " exists on compute nodes.");
            }
            log.println("\t  curl from compute node to: " + getJenkinsUrl());
            log.println("\t  JNLP port " + getJnlpPort() + " must be reachable from compute nodes");
        }

        log.println("");
    }

    /**
     * Prints the paths of the Slurm output and error files, prefixed with the
     * working directory when known.
     */
    private void logOutputFilePaths(PrintStream log, String jobId, String workDir) {
        String prefix = (workDir != null && !workDir.isEmpty()) ? workDir + "/" : "";
        log.println("\t  " + prefix + "slurm-" + jobId + ".out  (stdout)");
        log.println("\t  " + prefix + "slurm-" + jobId + ".err  (stderr, if separate)");
    }

    /** Returns the Jenkins URL from cloud location config, or a placeholder. */
    private String getJenkinsUrl() {
        JenkinsLocationConfiguration cfg = JenkinsLocationConfiguration.get();
        String url = cfg.getUrl();
        return (url != null && !url.isEmpty()) ? url : "<jenkins-url>";
    }

    private TaskListener consoleListener(SlurmAgent agent, TaskListener launchListener) {
        TaskListener runListener = agent.getRunListener();
        return runListener == TaskListener.NULL ? launchListener : runListener;
    }

    private void finalizeAgentPlacement(SlurmAgent agent, SlurmJobStatus status, TaskListener console) {
        if (status != null) {
            agent.applyJobPlacement(status);
            console.getLogger().println(status.formatConnectionMessage());
            return;
        }

        String jobId = agent.getSlurmJobId();
        String nodes = agent.getNodeList();
        if (jobId != null) {
            console.getLogger().println(new SlurmJobStatus(jobId, "RUNNING", null, nodes).formatConnectionMessage());
            agent.refreshNodeDescription();
        }
    }

    private void handleMissingSlurmJob(
            SlurmComputer computer,
            SlurmAgent agent,
            SlurmCloud cloud,
            SlurmJobTemplate template,
            String jobId,
            TaskListener listener)
            throws IOException, InterruptedException {
        String errorMsg = "Slurm job was cancelled or is no longer visible (job ID: " + jobId + ")";
        IOException exception = new IOException(errorMsg);
        setProblem(exception);
        SlurmCloudStats.attachLaunchingFail(agent, errorMsg);

        TaskListener console = consoleListener(agent, listener);
        console.getLogger().println("[Slurm] " + errorMsg);
        console.error(errorMsg);

        JobUtils.cancelQueueItemFor(agent, errorMsg);

        try {
            cloud.cancelJob(jobId, listener);
        } catch (Exception cancelEx) {
            LOGGER.log(Level.FINE, "Could not cancel missing Slurm job " + jobId, cancelEx);
        }

        computer.updateProvisioningStatus(new SlurmJobStatus(jobId, null, null, null));
        computer.setTemporarilyOffline(true, new OfflineCause.UserCause(null, errorMsg));

        try {
            agent.terminate();
        } catch (IOException | InterruptedException removeEx) {
            LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
        }

        throw exception;
    }

    private void failProvisioning(
            SlurmComputer computer,
            SlurmAgent agent,
            SlurmCloud cloud,
            SlurmJobTemplate template,
            String jobId,
            String jobState,
            String errorMsg,
            TaskListener listener,
            boolean cancelJob,
            Runnable logDetails)
            throws IOException, InterruptedException {
        IOException exception = new IOException(errorMsg);
        setProblem(exception);
        SlurmCloudStats.attachLaunchingFail(agent, SlurmCloudStats.formatFailureTitle(jobId, jobState, errorMsg));

        TaskListener console = consoleListener(agent, listener);
        logDetails.run();
        console.error(errorMsg);
        listener.error(errorMsg);
        LOGGER.severe(errorMsg);

        if (cancelJob) {
            try {
                cloud.cancelJob(jobId, listener);
            } catch (Exception cancelEx) {
                LOGGER.log(Level.WARNING, "Failed to cancel job " + jobId, cancelEx);
            }
        }

        JobUtils.cancelQueueItemFor(agent, errorMsg);
        computer.setTemporarilyOffline(true, new OfflineCause.UserCause(null, errorMsg));

        try {
            agent.terminate();
        } catch (IOException | InterruptedException removeEx) {
            LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
        }

        throw exception;
    }

    /**
     * Wait for agent to connect with active status checking.
     * This follows the Kubernetes plugin pattern:
     * - Actively polls job status while waiting
     * - Fails fast if job enters a failed state
     * - Times out with clear error message
     * - Logs progress periodically
     */
    private void waitForAgentConnection(
            SlurmComputer computer,
            SlurmAgent agent,
            SlurmCloud cloud,
            SlurmJobTemplate template,
            String jobId,
            long agentTimeoutMs,
            TaskListener listener)
            throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeout = startTime + agentTimeoutMs;
        long lastStatusCheck = 0;
        long lastProgressLog = startTime;
        boolean jobRunning = false;
        String lastLoggedStatus = null;
        SlurmJobStatus lastKnownStatus = null;
        Set<String> attachedJobStatusKeys = new HashSet<>();

        SlurmClient client = null;
        try {
            client = SlurmClientProvider.createClient(cloud);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create Slurm client for status checking", e);
            listener.getLogger()
                    .println("Warning: Could not create Slurm client for status checking. "
                            + "Will wait for agent connection without status monitoring.");
        }

        TaskListener console = consoleListener(agent, listener);

        while (System.currentTimeMillis() < timeout) {
            if (client != null && System.currentTimeMillis() - lastStatusCheck >= STATUS_CHECK_INTERVAL_MS) {
                try {
                    SlurmJobStatus status = client.getJobStatus(jobId);

                    if (status == null) {
                        handleMissingSlurmJob(computer, agent, cloud, template, jobId, listener);
                    }

                    lastKnownStatus = status;
                    computer.updateProvisioningStatus(status);
                    SlurmCloudStats.attachJobStatus(agent, status, startTime, attachedJobStatusKeys);
                    String statusLine = status.formatForDisplay();
                    if (!statusLine.equals(lastLoggedStatus)) {
                        console.getLogger().println(status.formatForConsole());
                        lastLoggedStatus = statusLine;
                    }

                    String jobState = status.getState();
                    if (jobState != null) {
                        if (jobState.equals("RUNNING")) {
                            if (!jobRunning) {
                                jobRunning = true;
                                console.getLogger().println(status.formatForConsole());
                                LOGGER.info("Slurm job " + jobId + " reached RUNNING state");
                            }
                        }

                        if ("CANCELLED".equals(jobState)) {
                            String errorMsg = "Slurm job was cancelled (job ID: " + jobId + ")";
                            failProvisioning(
                                    computer,
                                    agent,
                                    cloud,
                                    template,
                                    jobId,
                                    jobState,
                                    errorMsg,
                                    listener,
                                    false,
                                    () -> logProvisioningFailure(console, jobId, jobState, template));
                        }

                        if (SlurmClient.isFailedState(jobState)) {
                            String errorMsg = "Slurm job " + jobId + " failed with state: " + jobState + ". "
                                    + "Agent will not connect. Check Slurm logs: slurm-" + jobId + ".out";
                            LOGGER.severe("FAIL-FAST: Job " + jobId + " entered FAILED state: " + jobState);
                            failProvisioning(
                                    computer,
                                    agent,
                                    cloud,
                                    template,
                                    jobId,
                                    jobState,
                                    errorMsg,
                                    listener,
                                    true,
                                    () -> logProvisioningFailure(console, jobId, jobState, template));
                        }

                        if (jobState.equals("COMPLETED") && !computer.isOnline()) {
                            String errorMsg = "Slurm job " + jobId + " completed but agent failed to connect. "
                                    + "This usually means the startup script failed. Check Slurm logs: slurm-" + jobId
                                    + ".out";
                            LOGGER.severe("FAIL-FAST: Job " + jobId + " COMPLETED but agent never connected");
                            failProvisioning(
                                    computer,
                                    agent,
                                    cloud,
                                    template,
                                    jobId,
                                    jobState,
                                    errorMsg,
                                    listener,
                                    false,
                                    () -> logAgentConnectionFailure(console, jobId, template));
                        }
                    }

                    lastStatusCheck = System.currentTimeMillis();

                } catch (ApiException e) {
                    if (e.getCode() == 404) {
                        handleMissingSlurmJob(computer, agent, cloud, template, jobId, listener);
                    }
                    LOGGER.log(Level.FINE, "Failed to check job status for " + jobId, e);
                } catch (IOException ioe) {
                    throw ioe;
                }
            }

            if (!jobRunning && System.currentTimeMillis() - lastProgressLog >= PROGRESS_LOG_INTERVAL_MS) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                String progress = lastLoggedStatus != null
                        ? lastLoggedStatus + " — still waiting (" + elapsed + "s elapsed)"
                        : "Waiting for Slurm job to start running... (" + elapsed + " seconds elapsed)";
                console.getLogger().println(progress);
                lastProgressLog = System.currentTimeMillis();
            }

            // Check if agent is online
            // Kubernetes pattern: Both conditions must be met:
            // 1. Job is RUNNING (like pod ready)
            // 2. Computer is online (JNLP channel established)
            if (jobRunning && computer.isOnline()) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                finalizeAgentPlacement(agent, lastKnownStatus, console);
                console.getLogger()
                        .println("Agent connected successfully after " + elapsed
                                + " seconds (job RUNNING + JNLP connected)");
                LOGGER.info("Agent " + agent.getNodeName() + " connected successfully after " + elapsed
                        + " seconds (job RUNNING + JNLP connected)");
                SlurmCloudStats.attachLaunchingOk(
                        agent, "Agent connected after " + elapsed + "s (Slurm job " + jobId + ")");

                computer.clearProvisioningStatus();
                computer.setAcceptingTasks(true);
                launched = true;

                // Kubernetes pattern: persist the launched state
                try {
                    agent.save();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not save() agent: " + e.getMessage(), e);
                }

                return;
            }

            // Log if only one condition is met
            if (jobRunning && !computer.isOnline()) {
                if (System.currentTimeMillis() - lastProgressLog >= PROGRESS_LOG_INTERVAL_MS) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    console.getLogger()
                            .println(
                                    "Job is RUNNING, waiting for JNLP connection... (" + elapsed + " seconds elapsed)");
                    lastProgressLog = System.currentTimeMillis();
                }
            } else if (!jobRunning && computer.isOnline()) {
                // This shouldn't normally happen but log if it does
                LOGGER.warning("Agent connected but job not in RUNNING state yet");
            }

            // Sleep before next check
            Thread.sleep(1000);
        }

        // Timeout reached - cancel job and terminate node
        String timeoutReason = !jobRunning
                ? "job never reached RUNNING state"
                : !computer.isOnline() ? "JNLP connection never established" : "unknown reason";
        String errorMsg = "Timeout waiting for agent to launch after " + (agentTimeoutMs / 1000)
                + " seconds (" + timeoutReason + "). " + "Slurm job ID: "
                + jobId + ". Check Slurm job logs: slurm-" + jobId + ".out";

        // Set problem field to prevent retries
        IOException exception = new IOException(errorMsg);
        setProblem(exception);
        SlurmCloudStats.attachLaunchingFail(
                agent, SlurmCloudStats.formatFailureTitle(jobId, jobRunning ? "RUNNING" : "PENDING", errorMsg));

        logLaunchTimeout(console, jobId, template, jobRunning, agentTimeoutMs);

        listener.error(errorMsg);
        LOGGER.severe(errorMsg);

        // Cancel the Slurm job
        try {
            cloud.cancelJob(jobId, listener);
            LOGGER.info("Canceled timed-out Slurm job: " + jobId);
        } catch (Exception cancelEx) {
            LOGGER.log(Level.WARNING, "Failed to cancel job " + jobId, cancelEx);
        }

        // Kubernetes pattern: Cancel queue item to prevent infinite loop
        JobUtils.cancelQueueItemFor(agent, "Agent connection timeout: " + timeoutReason);

        // Mark computer offline
        computer.setTemporarilyOffline(true, new OfflineCause.UserCause(null, errorMsg));

        // Terminate node BEFORE throwing - prevents Jenkins from retrying launch on same computer
        try {
            agent.terminate();
            LOGGER.info("Terminated timed-out agent node: " + agent.getNodeName());
        } catch (IOException | InterruptedException removeEx) {
            LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
        }

        throw exception;
    }

    /**
     * Gets the Jenkins URL for agent connection.
     * Priority:
     * 1. Cloud configuration jenkinsUrl
     * 2. JenkinsLocationConfiguration URL
     * 3. Jenkins root URL
     * 4. Localhost fallback (for development)
     */
    private String getJenkinsUrl(SlurmCloud cloud) {
        // 1. Check cloud configuration
        if (cloud.getJenkinsUrl() != null && !cloud.getJenkinsUrl().trim().isEmpty()) {
            LOGGER.info("Using Jenkins URL from cloud configuration: " + cloud.getJenkinsUrl());
            return cloud.getJenkinsUrl();
        }

        // 2. Check JenkinsLocationConfiguration
        JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
        if (config != null) {
            String url = config.getUrl();
            if (url != null && !url.isEmpty()) {
                LOGGER.info("Using Jenkins URL from JenkinsLocationConfiguration: " + url);
                return url;
            }
        }

        // 3. Check Jenkins root URL
        Jenkins jenkins = Jenkins.get();
        String rootUrl = jenkins.getRootUrl();
        if (rootUrl != null && !rootUrl.isEmpty()) {
            LOGGER.info("Using Jenkins root URL: " + rootUrl);
            return rootUrl;
        }

        // 4. Development fallback - use localhost with default port
        String fallbackUrl = "http://localhost:8080/jenkins/";
        LOGGER.warning("Jenkins URL not configured anywhere - using fallback: " + fallbackUrl);
        LOGGER.warning(
                "Please configure Jenkins URL in: Manage Jenkins > System > Jenkins Location OR in the Slurm Cloud configuration");
        return fallbackUrl;
    }

    /**
     * Gets the JNLP secret for the agent.
     */
    private String getAgentSecret(SlurmComputer computer) {
        String secret = computer.getJnlpMac();
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("Failed to get JNLP secret for agent");
        }
        return secret;
    }

    /**
     * Gets the JNLP port for agent connections.
     * Returns "JNLP" if using WebSocket, or the actual port number.
     */
    private String getJnlpPort() {
        try {
            Jenkins jenkins = Jenkins.get();
            int port = jenkins.getTcpSlaveAgentListener() != null
                    ? jenkins.getTcpSlaveAgentListener().getAdvertisedPort()
                    : -1;
            if (port > 0) {
                return String.valueOf(port);
            }
            return "JNLP/WebSocket";
        } catch (Exception e) {
            return "JNLP";
        }
    }

    /**
     * The last problem that occurred, if any.
     * Following Kubernetes plugin pattern.
     *
     * @return the last problem, or null if no problem
     */
    @CheckForNull
    public Throwable getProblem() {
        return problem;
    }

    /**
     * Sets the problem that occurred during launch.
     * Following Kubernetes plugin pattern - stores first failure to prevent retries.
     *
     * @param problem the problem that occurred
     */
    public void setProblem(@CheckForNull Throwable problem) {
        this.problem = problem;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Launch Slurm agent";
        }
    }
}
