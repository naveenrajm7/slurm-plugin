package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

/**
 * Attaches Slurm-specific context to cloud-stats {@link ProvisioningActivity} records.
 *
 * <p>Failures and state transitions during agent launch are recorded in the LAUNCHING phase so
 * operators can inspect them under Manage Jenkins → Cloud Statistics or via
 * {@code /cloud-stats/api/json}.
 */
final class SlurmCloudStats {

    static final long PENDING_WARN_AFTER_MS = 60_000L;

    private static final Logger LOGGER = Logger.getLogger(SlurmCloudStats.class.getName());

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
     * Records a Slurm job status change once per distinct display line, and emits a WARN if the job
     * stays PENDING longer than {@link #PENDING_WARN_AFTER_MS}.
     */
    static void attachJobStatus(
            @NonNull SlurmAgent agent,
            @NonNull SlurmJobStatus status,
            long waitStartedAtMs,
            @NonNull java.util.Set<String> attachedKeys) {
        String statusKey = "status:" + status.formatForDisplay();
        if (attachedKeys.add(statusKey)) {
            ProvisioningActivity.Status level = attachmentLevelForStatus(status);
            attach(agent, level, status.formatForDisplay());
        }

        if (isLongPending(status, waitStartedAtMs) && attachedKeys.add("pending:long-wait")) {
            long elapsedSec = (System.currentTimeMillis() - waitStartedAtMs) / 1000;
            attach(
                    agent,
                    ProvisioningActivity.Status.WARN,
                    "Slurm job " + status.getJobId() + " still PENDING after " + elapsedSec
                            + "s"
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
        if ("PENDING".equals(state)) {
            return ProvisioningActivity.Status.OK;
        }
        return ProvisioningActivity.Status.OK;
    }

    static boolean isLongPending(@NonNull SlurmJobStatus status, long waitStartedAtMs) {
        return "PENDING".equals(status.getState())
                && System.currentTimeMillis() - waitStartedAtMs >= PENDING_WARN_AFTER_MS;
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

    private static String formatReasonSuffix(@NonNull SlurmJobStatus status) {
        String reason = status.getStateReason();
        if (reason == null || reason.isBlank() || "None".equalsIgnoreCase(reason)) {
            return "";
        }
        return " (" + reason + ")";
    }

    private static void attach(
            @NonNull SlurmAgent agent, @NonNull ProvisioningActivity.Status status, @NonNull String title) {
        attach(agent, new PhaseExecutionAttachment(status, title));
    }

    private static void attach(@NonNull SlurmAgent agent, @NonNull PhaseExecutionAttachment attachment) {
        try {
            CloudStatistics stats = CloudStatistics.get();
            ProvisioningActivity activity = stats.getActivityFor(agent);
            if (activity == null) {
                LOGGER.fine("No cloud-stats activity for agent " + agent.getNodeName());
                return;
            }
            stats.attach(activity, ProvisioningActivity.Phase.LAUNCHING, attachment);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Unable to attach cloud-stats record for " + agent.getNodeName(), e);
        }
    }
}
