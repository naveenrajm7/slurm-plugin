package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Queue;
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
 * - Retries a transient slurmrestd submit, and does not abort the build when no node was allocated
 * - Fails the claimed build when Slurm rejects the job spec, or after the job was allocated and then broke
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

    /**
     * Grace period after job submit before treating a missing REST job status as terminal.
     * slurmrestd may return 404 or an empty {@code job_state} briefly after POST /job/submit
     * even while the job is already running in Slurm.
     */
    private static final long JOB_STATUS_VISIBILITY_GRACE_MS = 60_000;

    /** Attempts for one launch, including the first. Transient failures sleep between the rest. */
    static final int SUBMIT_ATTEMPTS = 4;

    private static final long[] DEFAULT_SUBMIT_BACKOFF_MS = {500L, 1000L, 2000L};

    private volatile boolean launched = false;

    /** Override for tests. Production uses {@link #DEFAULT_SUBMIT_BACKOFF_MS}. */
    long[] submitBackoffMs = DEFAULT_SUBMIT_BACKOFF_MS.clone();

    /** When &gt;= 0, replaces the cloud agent timeout. Tests use 0 to take the timeout path immediately. */
    long agentTimeoutOverrideMs = -1L;

    /** Override for tests so a status transition does not wait out the production interval. */
    long statusCheckIntervalMs = STATUS_CHECK_INTERVAL_MS;

    /** Override for tests so the connect loop does not sleep a full second. */
    long connectionPollMs = 1000L;

    /**
     * When set, job status comes from here instead of slurmrestd. Tests use it to simulate
     * PENDING and post-allocation failure without a cluster.
     */
    @CheckForNull
    JobStatusSource jobStatusSource;

    /**
     * Provisioning exception if any.
     * Following Kubernetes plugin pattern: stores the first failure to prevent retries.
     */
    @CheckForNull
    private transient Throwable problem;

    @Override
    public boolean isLaunchSupported() {
        return !launched;
    }

    @Override
    public synchronized void launch(SlaveComputer computer, TaskListener listener) {
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
            agent.releaseProvisioningOwner();
            return;
        }

        // Kubernetes pattern: if already launched, just activate and return
        if (launched) {
            LOGGER.info("Agent already launched, activating: " + agent.getNodeName());
            markAgentReady(computer);
            agent.releaseProvisioningOwner();
            return;
        }
        if (computer.isOnline() && agent.getSlurmJobId() != null) {
            LOGGER.info("Agent already connected for: " + agent.getNodeName());
            markAgentReady(computer);
            launched = true;
            agent.releaseProvisioningOwner();
            return;
        }

        SlurmCloud cloud = agent.getSlurmCloud();
        SlurmJobTemplate template = cloud.getTemplateById(agent.getTemplateId());
        if (template == null) {
            throw new IllegalStateException("Template not found with ID: " + agent.getTemplateId());
        }

        // OnceRetentionStrategy may call connect() again while the Slurm job is already running.
        // Never submit a second job for the same agent node.
        String existingJobId = agent.getSlurmJobId();
        if (existingJobId != null) {
            LOGGER.info("Slurm job already submitted for " + agent.getNodeName() + " (job " + existingJobId
                    + "); waiting for agent connection only");
            listener.getLogger().println("Slurm job already submitted with ID: " + existingJobId);
            slurmComputer.setLaunching(true);
            try {
                waitForAgentConnection(
                        slurmComputer, agent, cloud, template, existingJobId, agentTimeoutMs(cloud), listener);
            } catch (ProvisioningOwnerGoneException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (HandledLaunchException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (InterruptedException e) {
                cleanupAbandonedLaunch(slurmComputer, agent, cloud, listener, e, false);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Launch interrupted", e);
            } catch (IOException e) {
                cleanupAbandonedLaunch(slurmComputer, agent, cloud, listener, e, false);
                throw new RuntimeException("Failed to wait for Slurm agent connection", e);
            } finally {
                slurmComputer.setLaunching(false);
            }
            return;
        }

        LOGGER.info("Launching Slurm agent: " + agent.getNodeName());
        listener.getLogger().println("Launching Slurm agent: " + agent.getNodeName());

        try {
            LOGGER.info("Got cloud: " + cloud.name);

            LOGGER.info("Using template: " + template.getName());
            listener.getLogger().println("Using template: " + template.getName());

            // Set the launching state
            slurmComputer.setLaunching(true);
            LOGGER.info("Set launching state to true");

            try {
                // Build the job description
                LOGGER.info("Building Slurm job description...");
                SlurmJobBuilder builder = new SlurmJobBuilder(
                        template, agent.getNodeName(), getJenkinsUrl(cloud), getAgentSecret(slurmComputer), cloud);
                JobDescMsg jobDesc = builder.build();
                LOGGER.info("Job description built successfully");

                // The build may have been aborted while the job description was built.
                // Do not submit a Slurm job nobody is waiting for.
                abandonIfBuildFinished(slurmComputer, agent, cloud, agent.getSlurmJobId(), listener);

                // OnceRetentionStrategy may call launch() concurrently. Submit at most one job.
                String jobId;
                synchronized (this) {
                    jobId = agent.getSlurmJobId();
                    if (jobId == null) {
                        LOGGER.info("Submitting job to Slurm...");
                        listener.getLogger().println("Submitting job to Slurm...");
                        jobId = submitJobWithRetry(cloud, jobDesc, console, slurmComputer, agent, listener);
                        agent.setSlurmJobId(jobId);
                        recordSubmittedJob(agent, jobId);
                        SlurmCloudStats.attachLaunchingOk(agent, "Submitted Slurm job " + jobId);
                        LOGGER.info("Slurm job submitted with ID: " + jobId);
                        listener.getLogger().println("Slurm job submitted with ID: " + jobId);
                    } else {
                        LOGGER.info("Slurm job already submitted for " + agent.getNodeName() + " (job " + jobId + ")");
                        listener.getLogger().println("Slurm job already submitted with ID: " + jobId);
                    }
                }

                // Wait for the agent to connect with active status checking
                long agentTimeoutMs = agentTimeoutMs(cloud);
                LOGGER.info("Waiting for agent to connect via WebSocket/JNLP (timeout: " + (agentTimeoutMs / 1000)
                        + " seconds)...");
                listener.getLogger()
                        .println("Waiting for agent to connect (timeout: " + (agentTimeoutMs / 1000) + " seconds)...");
                waitForAgentConnection(slurmComputer, agent, cloud, template, jobId, agentTimeoutMs, listener);

            } finally {
                slurmComputer.setLaunching(false);
                LOGGER.info("Set launching state to false");
            }

        } catch (ProvisioningOwnerGoneException e) {
            // Job cancel and node removal already happened. Do not cancel other builds
            // waiting on this label — only the build that owned this agent is finished.
            throw new RuntimeException(e.getMessage(), e);
        } catch (HandledLaunchException e) {
            // The wait path already removed this agent and, when the job had been allocated,
            // cancelled only the claimed build.
            throw new RuntimeException(e.getMessage(), e);
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Interrupted while launching Slurm agent: " + agent.getNodeName(), e);
            cleanupAbandonedLaunch(slurmComputer, agent, cloud, listener, e, false);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Launch interrupted", e);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to launch Slurm agent: " + agent.getNodeName(), e);
            // A real HTTP 4xx is a bad job spec. Retrying it would loop, so fail only the
            // build this agent claimed. Transient submit errors are not this case.
            boolean failClaimedBuild = agent.getSlurmJobId() == null && isPermanentSubmitRejection(e);
            cleanupAbandonedLaunch(slurmComputer, agent, cloud, listener, e, failClaimedBuild);
            throw new RuntimeException("Failed to launch Slurm agent", e);
        }
    }

    /**
     * Submits the job, retrying a malformed or unavailable slurmrestd response.
     * A real HTTP 4xx is returned to the caller on the first attempt.
     */
    private String submitJobWithRetry(
            SlurmCloud cloud,
            JobDescMsg jobDesc,
            TaskListener console,
            SlurmComputer computer,
            SlurmAgent agent,
            TaskListener listener)
            throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= SUBMIT_ATTEMPTS; attempt++) {
            abandonIfBuildFinished(computer, agent, cloud, agent.getSlurmJobId(), listener);
            try {
                return cloud.submitJob(jobDesc, console);
            } catch (ProvisioningOwnerGoneException e) {
                throw e;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                if (!isTransientSubmitFailure(e) || attempt == SUBMIT_ATTEMPTS) {
                    throw e;
                }
                long delay = submitBackoffMs[Math.min(attempt - 1, submitBackoffMs.length - 1)];
                String detail = e.getMessage() != null ? e.getMessage() : e.toString();
                console.getLogger()
                        .println("[Slurm] Submit failed (" + detail + "), retrying (" + (attempt + 1) + "/"
                                + SUBMIT_ATTEMPTS + ")...");
                LOGGER.log(
                        Level.WARNING,
                        "Transient Slurm submit failure for " + agent.getNodeName() + ", attempt " + attempt,
                        e);
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("Slurm submit failed");
    }

    /**
     * A submit that never reached Slurm in a durable way: malformed HTTP, connection loss, or HTTP 5xx.
     * {@code Invalid status line: "HTTP/0.9 400 BAD REQUEST"} is this case, not a Slurm 400.
     */
    static boolean isTransientSubmitFailure(@CheckForNull Throwable error) {
        if (chainMentionsInvalidStatusLine(error)) {
            // A bogus status line such as HTTP/0.9 400 is not a Slurm rejection.
            return true;
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (isConnectionFailure(current)) {
                return true;
            }
            if (current instanceof ApiException api) {
                int code = api.getCode();
                if (code == 0 || code >= 500) {
                    return true;
                }
                if (code >= 400 && code < 500) {
                    return false;
                }
            }
            if (current instanceof IOException && !(current instanceof ApiException)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Slurm rejected the job description. Retrying cannot succeed, so the claimed build should fail.
     */
    static boolean isPermanentSubmitRejection(@CheckForNull Throwable error) {
        if (error == null || isTransientSubmitFailure(error)) {
            return false;
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ApiException api) {
                int code = api.getCode();
                return code >= 400 && code < 500;
            }
        }
        return false;
    }

    private static boolean chainMentionsInvalidStatusLine(@CheckForNull Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("Invalid status line")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectionFailure(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("broken pipe");
    }

    /**
     * Removes this agent after a launch that the wait loop did not already finish.
     * Cancels the claimed build only when {@code cancelClaimedBuild} is set.
     */
    private void cleanupAbandonedLaunch(
            SlurmComputer computer,
            SlurmAgent agent,
            SlurmCloud cloud,
            TaskListener listener,
            Throwable error,
            boolean cancelClaimedBuild) {
        String detail = error.getMessage() != null ? error.getMessage() : error.toString();
        boolean submitFailure = agent.getSlurmJobId() == null && !(error instanceof InterruptedException);
        String errorMsg = submitFailure ? "Slurm submit failed: " + detail : detail;
        if (error instanceof InterruptedException) {
            errorMsg = "Launch interrupted: " + detail;
        }
        setProblem(new IOException(errorMsg, error));
        SlurmCloudStats.attachLaunchingFail(agent, error);

        TaskListener console = consoleListener(agent, listener);
        console.getLogger().println("[Slurm] " + errorMsg);
        console.error(errorMsg);
        listener.error(errorMsg);
        if (submitFailure && !cancelClaimedBuild) {
            console.getLogger().println("[Slurm] The build is still queued and will be provisioned again.");
        }

        cancelJobOnFailure(agent, cloud, listener);
        if (cancelClaimedBuild) {
            JobUtils.cancelQueueItemFor(agent, errorMsg);
        }
        if (computer != null) {
            computer.setTemporarilyOffline(true, new OfflineCause.UserCause(null, errorMsg));
        }
        removeNodeOnFailure(agent);
    }

    private long agentTimeoutMs(SlurmCloud cloud) {
        if (agentTimeoutOverrideMs >= 0) {
            return agentTimeoutOverrideMs;
        }
        return (long) cloud.getAgentTimeoutMinutes() * 60L * 1000L;
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

    /**
     * Marks the agent schedulable and nudges the queue so pipeline {@code node('label')} blocks
     * assign work promptly after JNLP connect (before OnceRetentionStrategy idle teardown).
     */
    private void markAgentReady(SlaveComputer computer) {
        computer.setAcceptingTasks(true);
        SlurmAgent agent = computer instanceof SlurmComputer slurmComputer ? slurmComputer.getNode() : null;
        if (agent != null && agent.getSlurmJobId() != null) {
            SlurmJobHeartbeats.refresh(agent.getCloudName(), agent.getSlurmJobId(), agent.getNodeName());
        }
        Queue.getInstance().maintain();
    }

    private void recordSubmittedJob(SlurmAgent agent, String jobId) {
        SlurmJobHeartbeats.refresh(agent.getCloudName(), jobId, agent.getNodeName());
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

    private static boolean shouldTreatJobAsMissing(SlurmComputer computer, long submitTimeMs) {
        if (computer.isOnline()) {
            return false;
        }
        return System.currentTimeMillis() - submitTimeMs > JOB_STATUS_VISIBILITY_GRACE_MS;
    }

    private void handleMissingSlurmJob(
            SlurmComputer computer,
            SlurmAgent agent,
            SlurmCloud cloud,
            SlurmJobTemplate template,
            String jobId,
            TaskListener listener,
            boolean jobWasRunning)
            throws IOException, InterruptedException {
        String errorMsg = "Slurm job was cancelled or is no longer visible (job ID: " + jobId + ")";
        // A job that disappears before RUNNING never secured a node. Leave the build queued.
        failProvisioning(
                computer,
                agent,
                cloud,
                template,
                jobId,
                null,
                errorMsg,
                listener,
                true,
                jobWasRunning,
                () -> listener.getLogger().println("[Slurm] " + errorMsg));
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
            boolean failClaimedBuild,
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

        if (failClaimedBuild) {
            JobUtils.cancelQueueItemFor(agent, errorMsg);
        } else {
            console.getLogger().println("[Slurm] The build is still queued and will be provisioned again.");
        }
        computer.setTemporarilyOffline(true, new OfflineCause.UserCause(null, errorMsg));
        computer.updateProvisioningStatus(new SlurmJobStatus(jobId, jobState, null, null));

        try {
            agent.terminate();
        } catch (IOException | InterruptedException removeEx) {
            LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
        }

        throw new HandledLaunchException(errorMsg);
    }

    /**
     * Wait for agent to connect with active status checking.
     * This follows the Kubernetes plugin pattern:
     * - Actively polls job status while waiting
     * - Fails fast if job enters a failed state
     * - Times out with clear error message
     * - Logs progress periodically
     * - Stops when the build that requested this agent is aborted or finishes first
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
        if (jobStatusSource == null) {
            try {
                client = SlurmClientProvider.createClient(cloud);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to create Slurm client for status checking", e);
                listener.getLogger()
                        .println("Warning: Could not create Slurm client for status checking. "
                                + "Will wait for agent connection without status monitoring.");
            }
        }

        TaskListener console = consoleListener(agent, listener);

        // Aborting the build interrupts the thread blocked in node(), not this launcher thread.
        abandonIfBuildFinished(computer, agent, cloud, jobId, listener);
        while (System.currentTimeMillis() < timeout) {
            abandonIfBuildFinished(computer, agent, cloud, jobId, listener);
            JobStatusSource statusSource = jobStatusSource;
            boolean canPollStatus = statusSource != null || client != null;
            if (canPollStatus && System.currentTimeMillis() - lastStatusCheck >= statusCheckIntervalMs) {
                try {
                    SlurmJobStatus status;
                    if (statusSource != null) {
                        status = statusSource.getJobStatus(jobId);
                    } else if (client != null) {
                        status = client.getJobStatus(jobId);
                    } else {
                        status = null;
                    }

                    if (status == null) {
                        if (shouldTreatJobAsMissing(computer, startTime)) {
                            handleMissingSlurmJob(computer, agent, cloud, template, jobId, listener, jobRunning);
                        } else {
                            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                            console.getLogger()
                                    .println("[Slurm] Job status not yet visible from slurmrestd (" + elapsed
                                            + "s since submit), waiting...");
                            LOGGER.fine("Job " + jobId + " status not visible yet; within grace period");
                            lastStatusCheck = System.currentTimeMillis();
                            // fall through to online check below
                        }
                    } else {
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
                                // Cancelled before the job ever ran: the build did not get a node.
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
                                        jobRunning,
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
                                        true,
                                        () -> logProvisioningFailure(console, jobId, jobState, template));
                            }

                            if (jobState.equals("COMPLETED") && !computer.isOnline()) {
                                String errorMsg = "Slurm job " + jobId + " completed but agent failed to connect. "
                                        + "This usually means the startup script failed. Check Slurm logs: slurm-"
                                        + jobId + ".out";
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
                                        true,
                                        () -> logAgentConnectionFailure(console, jobId, template));
                            }
                        }

                        lastStatusCheck = System.currentTimeMillis();
                    }

                } catch (ApiException e) {
                    if (e.getCode() == 404) {
                        if (shouldTreatJobAsMissing(computer, startTime)) {
                            handleMissingSlurmJob(computer, agent, cloud, template, jobId, listener, jobRunning);
                        } else {
                            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                            console.getLogger()
                                    .println("[Slurm] Job status not yet visible from slurmrestd (HTTP 404, " + elapsed
                                            + "s since submit), waiting...");
                            LOGGER.fine("Job " + jobId + " returned 404; within grace period");
                            lastStatusCheck = System.currentTimeMillis();
                        }
                    } else {
                        LOGGER.log(Level.FINE, "Failed to check job status for " + jobId, e);
                    }
                } catch (IOException ioe) {
                    throw ioe;
                } catch (Exception e) {
                    throw new IOException("Failed to read Slurm job status for " + jobId, e);
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

            // Agent online means the batch script started remoting; accept even if slurmrestd
            // status polling is delayed or returns empty job_state.
            if (computer.isOnline()) {
                if (!jobRunning) {
                    LOGGER.info("Agent " + agent.getNodeName() + " connected before slurmrestd reported RUNNING");
                    jobRunning = true;
                }
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                finalizeAgentPlacement(agent, lastKnownStatus, console);
                console.getLogger()
                        .println("Agent connected successfully after " + elapsed
                                + " seconds (JNLP connected"
                                + (lastKnownStatus != null && "RUNNING".equals(lastKnownStatus.getState())
                                        ? ", job RUNNING"
                                        : "")
                                + ")");
                LOGGER.info("Agent " + agent.getNodeName() + " connected successfully after " + elapsed + " seconds");
                SlurmCloudStats.attachLaunchingOk(
                        agent, "Agent connected after " + elapsed + "s (Slurm job " + jobId + ")");

                computer.clearProvisioningStatus();
                markAgentReady(computer);
                agent.releaseProvisioningOwner();
                launched = true;

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
            }

            abandonIfBuildFinished(computer, agent, cloud, jobId, listener);

            // Sleep before next check
            Thread.sleep(Math.max(0L, connectionPollMs));
        }

        // Timeout. A job that never reached RUNNING did not secure a node: cancel that Slurm
        // job so it cannot start later, and leave the Jenkins build queued. A job that was
        // already RUNNING and never connected is a broken agent startup, so fail the build.
        String timeoutReason = !jobRunning
                ? "job never reached RUNNING state"
                : !computer.isOnline() ? "JNLP connection never established" : "unknown reason";
        String errorMsg = "Timeout waiting for agent to launch after " + (agentTimeoutMs / 1000)
                + " seconds (" + timeoutReason + "). " + "Slurm job ID: "
                + jobId + ". Check Slurm job logs: slurm-" + jobId + ".out";
        boolean reachedRunning = jobRunning;
        failProvisioning(
                computer,
                agent,
                cloud,
                template,
                jobId,
                reachedRunning ? "RUNNING" : "PENDING",
                errorMsg,
                listener,
                true,
                reachedRunning,
                () -> logLaunchTimeout(console, jobId, template, reachedRunning, agentTimeoutMs));
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
     * Stops the pre-connect wait when the build paired with this agent has finished.
     * A live agent is left for {@link #waitForAgentConnection} to accept.
     */
    private void abandonIfBuildFinished(
            SlurmComputer computer, SlurmAgent agent, SlurmCloud cloud, String jobId, TaskListener listener)
            throws IOException, InterruptedException {
        if (computer.isOnline() || !shouldStopWaitingForBuild(agent)) {
            return;
        }
        stopForFinishedBuild(computer, agent, cloud, jobId, listener);
    }

    /**
     * True when the build this agent was provisioned for left the queue or finished
     * before the agent connected.
     */
    static boolean shouldStopWaitingForBuild(SlurmAgent agent) {
        Long queueItemId = agent.getProvisioningQueueItemId();
        if (queueItemId == null) {
            return false;
        }
        return JobUtils.provisioningOwnerReleased(queueItemId, agent.getProvisioningRun());
    }

    /**
     * Cancels this agent's Slurm job and deletes the node. Other builds waiting on the same
     * label keep their own queue items and get their own agents.
     *
     * <p>A queue item another executor already started is not a launch failure: the build is
     * still running, so the spare job is cancelled quietly and omitted from the failure stats.
     * A build that ended, was aborted, or whose queue item was cancelled stays a launch failure.
     */
    void stopForFinishedBuild(
            SlurmComputer computer, SlurmAgent agent, SlurmCloud cloud, String jobId, TaskListener listener)
            throws IOException {
        Long queueItemId = agent.getProvisioningQueueItemId();
        JobUtils.ProvisioningRelease release = queueItemId == null
                ? JobUtils.ProvisioningRelease.BUILD_FINISHED
                : JobUtils.classifyProvisioningRelease(queueItemId, agent.getProvisioningRun());
        boolean handoff = release == JobUtils.ProvisioningRelease.TAKEN_BY_ANOTHER_EXECUTOR;
        String message = handoff ? handoffMessage(jobId) : finishedBeforeConnectMessage(agent);

        TaskListener console = consoleListener(agent, listener);
        if (handoff) {
            console.getLogger().println("[Slurm] " + message);
            if (console != listener) {
                listener.getLogger().println("[Slurm] " + message);
            }
            SlurmCloudStats.attachLaunchingOk(agent, message);
        } else {
            console.getLogger().println("[Slurm] Agent provisioning interrupted: " + message);
            console.error(message);
            if (console != listener) {
                listener.getLogger().println("[Slurm] Agent provisioning interrupted: " + message);
            }
            SlurmCloudStats.attachLaunchingFail(agent, message);
        }

        ProvisioningOwnerGoneException failure = new ProvisioningOwnerGoneException(message);
        setProblem(failure);
        LOGGER.info(message);

        if (jobId != null && cloud != null) {
            cloud.cancelJob(jobId, console);
            if (handoff) {
                SlurmCloudStats.attachLaunchingOk(agent, "Slurm job " + jobId + " cancelled");
            }
        }

        // A handoff already started on another executor. Cancelling that left item would abort it.
        if (!handoff && queueItemId != null) {
            Queue.Item item = Jenkins.get().getQueue().getItem(queueItemId);
            if (item != null && !(item instanceof Queue.LeftItem)) {
                Jenkins.get().getQueue().cancel(item);
            }
        }

        if (computer != null) {
            computer.setTemporarilyOffline(true, new OfflineCause.UserCause(null, message));
        }

        try {
            agent.terminate();
            LOGGER.info("Terminated agent " + agent.getNodeName()
                    + (handoff
                            ? " after another executor took the queue item"
                            : " after the build finished before connect"));
        } catch (IOException | InterruptedException removeEx) {
            LOGGER.log(Level.WARNING, "Failed to terminate node " + agent.getNodeName(), removeEx);
        }

        throw failure;
    }

    private static String finishedBeforeConnectMessage(SlurmAgent agent) {
        return "Build finished before agent " + agent.getNodeName() + " connected";
    }

    private static String handoffMessage(@CheckForNull String jobId) {
        if (jobId != null) {
            return "Queue item taken by another executor; cancelling Slurm job " + jobId;
        }
        return "Queue item taken by another executor; no Slurm job was submitted";
    }

    /** Status reads used by tests in place of slurmrestd. */
    @FunctionalInterface
    interface JobStatusSource {
        @CheckForNull
        SlurmJobStatus getJobStatus(String jobId) throws Exception;
    }

    /**
     * The wait loop already removed the agent and decided whether the claimed build fails.
     * The outer {@code launch} catch must not cancel a second queue item.
     */
    static final class HandledLaunchException extends IOException {
        private static final long serialVersionUID = 1L;

        HandledLaunchException(String message) {
            super(message);
        }
    }

    /** The build that caused provisioning ended before this launcher finished connecting. */
    static final class ProvisioningOwnerGoneException extends IOException {
        private static final long serialVersionUID = 1L;

        ProvisioningOwnerGoneException(String message) {
            super(message);
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
