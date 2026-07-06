package io.jenkins.plugins.slurm.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Snapshot of a Slurm job's state from the REST API.
 */
public final class SlurmJobStatus {

    private final String jobId;
    @CheckForNull
    private final String state;
    @CheckForNull
    private final String stateReason;
    @CheckForNull
    private final String nodes;

    public SlurmJobStatus(
            @NonNull String jobId,
            @CheckForNull String state,
            @CheckForNull String stateReason,
            @CheckForNull String nodes) {
        this.jobId = jobId;
        this.state = state;
        this.stateReason = stateReason;
        this.nodes = nodes;
    }

    @NonNull
    public String getJobId() {
        return jobId;
    }

    @CheckForNull
    public String getState() {
        return state;
    }

    @CheckForNull
    public String getStateReason() {
        return stateReason;
    }

    @CheckForNull
    public String getNodes() {
        return nodes;
    }

    public boolean isMissing() {
        return state == null;
    }

    /**
     * One-line status for build console and agent UI (e.g. {@code Slurm job 413835: PENDING (Priority)}).
     */
    @NonNull
    public String formatForDisplay() {
        if (isMissing()) {
            return "Slurm job " + jobId + ": not found (may have been cancelled)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Slurm job ").append(jobId).append(": ").append(state);
        if (stateReason != null && !stateReason.isBlank() && !"None".equalsIgnoreCase(stateReason)) {
            sb.append(" (").append(stateReason).append(")");
        }
        return sb.toString();
    }

    /**
     * Build-console line with a {@code [Slurm]} prefix.
     */
    @NonNull
    public String formatForConsole() {
        return "[Slurm] " + formatForDisplay();
    }
}
