package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.NodeListener;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

/**
 * Attaches Slurm-specific context to cloud-stats {@link ProvisioningActivity} records.
 *
 * <p>Launch lines (submit, pending, connect, and a spare job whose queue item was taken) stay on
 * {@link ProvisioningActivity.Phase#LAUNCHING}. {@link ProvisioningActivity.Status#FAIL} is only
 * used when the Slurm job itself failed or the agent never connected because of a real error. A
 * handoff to another executor is {@link ProvisioningActivity.Status#OK}. A Jenkins build that
 * finishes after the agent connected is recorded on {@link ProvisioningActivity.Phase#OPERATING}
 * or {@link ProvisioningActivity.Phase#COMPLETED}, and a non-success result is
 * {@link ProvisioningActivity.Status#WARN}.
 */
final class SlurmCloudStats {

    static final long PENDING_WARN_AFTER_MS = 60_000L;

    private static final Logger LOGGER = Logger.getLogger(SlurmCloudStats.class.getName());

    private static final String PENDING_SEEN = "pending:seen";
    private static final String PENDING_LEFT = "pending:left";
    private static final String PENDING_LONG_WAIT = "pending:long-wait";
    private static final String PENDING_REASON_PREFIX = "pending:reason:";

    /** Run externalizable id to the activities that executed it. */
    private static final Map<String, Set<ProvisioningActivity.Id>> ACTIVITIES_BY_RUN = new ConcurrentHashMap<>();

    private static final Set<String> RUNNING_RECORDED = ConcurrentHashMap.newKeySet();
    private static final Set<String> RESULT_RECORDED = ConcurrentHashMap.newKeySet();

    private SlurmCloudStats() {}

    static void attachLaunchingOk(@NonNull SlurmAgent agent, @NonNull String title) {
        attach(agent, ProvisioningActivity.Status.OK, title);
    }

    static void attachLaunchingWarn(@NonNull SlurmAgent agent, @NonNull String title) {
        attach(agent, ProvisioningActivity.Status.WARN, title);
    }

    static void attachLaunchingFail(@NonNull SlurmAgent agent, @NonNull String title) {
        attach(agent, ProvisioningActivity.Status.FAIL, title);
    }

    static void attachLaunchingFail(@NonNull SlurmAgent agent, @NonNull Throwable throwable) {
        attach(agent, new PhaseExecutionAttachment.ExceptionAttachment(ProvisioningActivity.Status.FAIL, throwable));
    }

    /**
     * Records a Slurm job status change once per distinct display line.
     *
     * <p>A pending job with no real node assignment says so, instead of naming the placeholder
     * {@code None assigned}. The one-shot long-wait line is a threshold ({@code longer than 60s}),
     * and leaving {@code PENDING} after that threshold adds the actual wait. Attachments cannot be
     * edited, so the threshold line is not rewritten in place.
     */
    static void attachJobStatus(
            @NonNull SlurmAgent agent,
            @NonNull SlurmJobStatus status,
            long waitStartedAtMs,
            @NonNull Set<String> attachedKeys) {
        notePendingTransition(agent, status, waitStartedAtMs, attachedKeys);

        String statusKey = "status:" + status.formatForDisplay();
        if (attachedKeys.add(statusKey)) {
            attach(agent, attachmentLevelForStatus(status), status.formatForDisplay());
        }

        if (isLongPending(status, waitStartedAtMs) && attachedKeys.add(PENDING_LONG_WAIT)) {
            attach(
                    agent,
                    ProvisioningActivity.Status.OK,
                    "Slurm job " + status.getJobId() + " PENDING longer than " + (PENDING_WARN_AFTER_MS / 1000) + "s"
                            + formatReasonSuffix(status));
        }
    }

    @NonNull
    static ProvisioningActivity.Status attachmentLevelForStatus(@NonNull SlurmJobStatus status) {
        String state = status.getState();
        if (state == null) {
            return ProvisioningActivity.Status.WARN;
        }
        if (SlurmClient.isFailedState(state) || "CANCELLED".equals(state)) {
            return ProvisioningActivity.Status.FAIL;
        }
        return ProvisioningActivity.Status.OK;
    }

    static boolean isLongPending(@NonNull SlurmJobStatus status, long waitStartedAtMs) {
        return "PENDING".equals(status.getState())
                && System.currentTimeMillis() - waitStartedAtMs >= PENDING_WARN_AFTER_MS;
    }

    @NonNull
    static String formatElapsed(long elapsedMs) {
        long seconds = Math.max(0, elapsedMs / 1000);
        if (seconds < 120) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long remainder = minutes % 60;
        if (remainder == 0) {
            return hours + " hr";
        }
        return hours + " hr " + remainder + " min";
    }

    @NonNull
    static String formatFailureTitle(
            @CheckForNull String jobId, @CheckForNull String jobState, @NonNull String message) {
        StringBuilder title = new StringBuilder(message);
        if (jobId != null && !jobId.isBlank()) {
            title.append(" (job ").append(jobId);
            if (jobState != null && !jobState.isBlank()) {
                title.append(", state ").append(jobState);
            }
            title.append(')');
        }
        return title.toString();
    }

    /**
     * Walks {@link Queue.Executable#getParentExecutable()} until the owning {@link Run}.
     * A pipeline {@code node} step is a placeholder whose parent is the {@code WorkflowRun}.
     */
    @CheckForNull
    static Run<?, ?> owningRun(@CheckForNull Queue.Executable executable) {
        Queue.Executable current = executable;
        Set<Queue.Executable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if (current instanceof Run<?, ?> run) {
                return run;
            }
            current = current.getParentExecutable();
        }
        return null;
    }

    /** Records that {@code run} is executing on this agent. The line is on the operating phase. */
    static void recordBuildStarted(@NonNull SlurmAgent agent, @NonNull Run<?, ?> run) {
        remember(run, agent.getId());
        if (!RUNNING_RECORDED.add(recordKey(run, agent.getId()))) {
            return;
        }
        attach(
                agent.getId(),
                ProvisioningActivity.Phase.OPERATING,
                ProvisioningActivity.Status.OK,
                "Running " + run.getFullDisplayName() + " " + buildUrl(run),
                ProvisioningActivity.Phase.OPERATING);
    }

    /**
     * Closes an agent that reached operation: the node is gone, and the build result is included
     * when it already exists. A pipeline that is still running gets the result later from
     * {@link #recordBuildFinished}.
     */
    static void recordAgentRemoved(@NonNull SlurmAgent agent) {
        ProvisioningActivity activity = activityFor(agent.getId());
        if (activity == null || activity.getPhaseExecution(ProvisioningActivity.Phase.OPERATING) == null) {
            return;
        }
        attach(
                agent.getId(),
                ProvisioningActivity.Phase.COMPLETED,
                ProvisioningActivity.Status.OK,
                "Agent removed",
                null);
        for (Run<?, ?> run : runsFor(agent)) {
            if (run.isBuilding()) {
                attach(
                        agent.getId(),
                        ProvisioningActivity.Phase.COMPLETED,
                        ProvisioningActivity.Status.OK,
                        "Build " + run.getFullDisplayName() + " still IN_PROGRESS " + buildUrl(run),
                        null);
            } else {
                recordResult(agent.getId(), run);
            }
        }
    }

    /** One completion line per activity that ran this build. A non-success result is a warning. */
    static void recordBuildFinished(@NonNull Run<?, ?> run) {
        Set<ProvisioningActivity.Id> ids = ACTIVITIES_BY_RUN.remove(run.getExternalizableId());
        if (ids == null) {
            return;
        }
        for (ProvisioningActivity.Id id : ids) {
            recordResult(id, run);
        }
    }

    static void clearForTest() {
        ACTIVITIES_BY_RUN.clear();
        RUNNING_RECORDED.clear();
        RESULT_RECORDED.clear();
    }

    @NonNull
    static ProvisioningActivity.Status statusFor(@CheckForNull Result result) {
        return Result.SUCCESS.equals(result) ? ProvisioningActivity.Status.OK : ProvisioningActivity.Status.WARN;
    }

    private static void recordResult(@NonNull ProvisioningActivity.Id id, @NonNull Run<?, ?> run) {
        if (!RESULT_RECORDED.add(recordKey(run, id))) {
            return;
        }
        Result result = run.getResult();
        String resultName = result != null ? result.toString() : "UNKNOWN";
        attach(
                id,
                ProvisioningActivity.Phase.COMPLETED,
                statusFor(result),
                "Build " + run.getFullDisplayName() + " finished " + resultName + " " + buildUrl(run),
                ProvisioningActivity.Phase.OPERATING);
    }

    private static void remember(@NonNull Run<?, ?> run, @NonNull ProvisioningActivity.Id id) {
        ACTIVITIES_BY_RUN
                .computeIfAbsent(run.getExternalizableId(), key -> new CopyOnWriteArraySet<>())
                .add(id);
    }

    @NonNull
    private static List<Run<?, ?>> runsFor(@NonNull SlurmAgent agent) {
        LinkedHashSet<Run<?, ?>> runs = new LinkedHashSet<>();
        for (Map.Entry<String, Set<ProvisioningActivity.Id>> entry : ACTIVITIES_BY_RUN.entrySet()) {
            if (!entry.getValue().contains(agent.getId())) {
                continue;
            }
            try {
                Run<?, ?> run = Run.fromExternalizableId(entry.getKey());
                if (run != null) {
                    runs.add(run);
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not resolve build " + entry.getKey(), e);
            }
        }
        if (runs.isEmpty() && agent.getProvisioningRun() != null) {
            runs.add(agent.getProvisioningRun());
        }
        return new ArrayList<>(runs);
    }

    @NonNull
    private static String recordKey(@NonNull Run<?, ?> run, @NonNull ProvisioningActivity.Id id) {
        return run.getExternalizableId() + "@" + id.getFingerprint();
    }

    @NonNull
    private static String buildUrl(@NonNull Run<?, ?> run) {
        try {
            String url = run.getAbsoluteUrl();
            if (url != null && !url.isBlank()) {
                return url;
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Jenkins root URL is not set; using the relative build URL", e);
        }
        return run.getUrl();
    }

    private static void notePendingTransition(
            @NonNull SlurmAgent agent,
            @NonNull SlurmJobStatus status,
            long waitStartedAtMs,
            @NonNull Set<String> attachedKeys) {
        if ("PENDING".equals(status.getState())) {
            attachedKeys.add(PENDING_SEEN);
            String reason = meaningfulReason(status);
            if (reason != null) {
                attachedKeys.add(PENDING_REASON_PREFIX + reason);
            }
            return;
        }
        if (!attachedKeys.contains(PENDING_SEEN) || !attachedKeys.add(PENDING_LEFT)) {
            return;
        }
        long elapsed = System.currentTimeMillis() - waitStartedAtMs;
        if (elapsed < PENDING_WARN_AFTER_MS) {
            return;
        }
        attach(
                agent,
                ProvisioningActivity.Status.OK,
                "Slurm job " + status.getJobId() + " was PENDING for " + formatElapsed(elapsed)
                        + reasonSuffixFromKeys(attachedKeys));
    }

    @CheckForNull
    private static String meaningfulReason(@NonNull SlurmJobStatus status) {
        String reason = status.getStateReason();
        if (reason == null || reason.isBlank() || "None".equalsIgnoreCase(reason)) {
            return null;
        }
        return reason;
    }

    private static String formatReasonSuffix(@NonNull SlurmJobStatus status) {
        String reason = meaningfulReason(status);
        return reason == null ? "" : " (" + reason + ")";
    }

    @NonNull
    private static String reasonSuffixFromKeys(@NonNull Set<String> attachedKeys) {
        for (String key : attachedKeys) {
            if (key.startsWith(PENDING_REASON_PREFIX)) {
                return " (" + key.substring(PENDING_REASON_PREFIX.length()) + ")";
            }
        }
        return "";
    }

    private static void attach(
            @NonNull SlurmAgent agent, @NonNull ProvisioningActivity.Status status, @NonNull String title) {
        attach(agent, new PhaseExecutionAttachment(status, title));
    }

    private static void attach(@NonNull SlurmAgent agent, @NonNull PhaseExecutionAttachment attachment) {
        attach(agent.getId(), ProvisioningActivity.Phase.LAUNCHING, attachment, null);
    }

    private static void attach(
            @NonNull ProvisioningActivity.Id id,
            @NonNull ProvisioningActivity.Phase phase,
            @NonNull ProvisioningActivity.Status status,
            @NonNull String title,
            @CheckForNull ProvisioningActivity.Phase fallback) {
        attach(id, phase, new PhaseExecutionAttachment(status, title), fallback);
    }

    /**
     * @param fallback used when {@code phase} has not been entered yet, so a result that arrives
     *     while the agent is still online lands on operation instead of being dropped
     */
    private static void attach(
            @NonNull ProvisioningActivity.Id id,
            @NonNull ProvisioningActivity.Phase phase,
            @NonNull PhaseExecutionAttachment attachment,
            @CheckForNull ProvisioningActivity.Phase fallback) {
        try {
            CloudStatistics stats = CloudStatistics.get();
            ProvisioningActivity activity = activityFor(id);
            if (activity == null) {
                LOGGER.fine("No cloud-stats activity for " + id);
                return;
            }
            ProvisioningActivity.Phase target = phase;
            if (activity.getPhaseExecution(target) == null) {
                if (fallback == null || activity.getPhaseExecution(fallback) == null) {
                    LOGGER.fine("Cloud-stats phase " + phase + " is not open for " + id);
                    return;
                }
                target = fallback;
            }
            stats.attach(activity, target, attachment);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Unable to attach cloud-stats record for " + id, e);
        }
    }

    @CheckForNull
    private static ProvisioningActivity activityFor(@NonNull ProvisioningActivity.Id id) {
        return CloudStatistics.get().getPotentiallyCompletedActivityFor(id);
    }

    /**
     * Remembers the build when an executor on a Slurm agent starts it.
     * The activity id is what lets the completion line survive after the computer is deleted.
     */
    @Extension
    public static final class BuildExecutorListener implements ExecutorListener {
        @Override
        public void taskStarted(Executor executor, Queue.Task task) {
            Computer computer = executor.getOwner();
            if (!(computer instanceof SlurmComputer slurmComputer)) {
                return;
            }
            SlurmAgent agent = slurmComputer.getNode();
            if (agent == null) {
                return;
            }
            Queue.Executable executable = executor.getCurrentExecutable();
            if (executable == null && task instanceof Queue.Executable queueExecutable) {
                executable = queueExecutable;
            }
            Run<?, ?> run = owningRun(executable);
            if (run == null) {
                return;
            }
            recordBuildStarted(agent, run);
        }
    }

    /**
     * Writes the build result onto each activity that ran the build.
     * The raw listener is required: {@code RunListener<Run<?, ?>>} is not a class token and
     * Jenkins cannot infer it.
     */
    @Extension
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final class BuildCompletedListener extends RunListener {
        public BuildCompletedListener() {
            super(Run.class);
        }

        @Override
        public void onCompleted(Run run, TaskListener listener) {
            recordBuildFinished(run);
        }
    }

    /**
     * Runs after cloud-stats {@code SlaveCompletionDetector} (ordinal 0), which enters
     * {@link ProvisioningActivity.Phase#COMPLETED}. A later sentence has to be attached after that
     * phase exists.
     */
    @Extension(ordinal = -10000)
    public static final class AgentRemovedListener extends NodeListener {
        @Override
        protected void onDeleted(@NonNull Node node) {
            if (node instanceof SlurmAgent agent) {
                recordAgentRemoved(agent);
            }
        }
    }
}
