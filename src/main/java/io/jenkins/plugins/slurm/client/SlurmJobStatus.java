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
        appendReason(sb);
        String assigned = assignedNodes();
        if (assigned != null) {
            sb.append(" on ").append(assigned);
        } else if ("PENDING".equals(state)) {
            sb.append(", no node yet");
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

    /**
     * Build-console line when the agent connects and compute placement is known.
     */
    @NonNull
    public String formatConnectionMessage() {
        String assigned = assignedNodes();
        if (assigned != null) {
            return "[Slurm] Slurm job " + jobId + " on node(s) " + assigned;
        }
        return "[Slurm] Slurm job " + jobId + " connected (compute node not reported by Slurm yet)";
    }

    /**
     * Compute node names Slurm has actually assigned.
     *
     * <p>Pending jobs often report the placeholder {@code None assigned}. That is not a hostname.
     */
    @CheckForNull
    public String assignedNodes() {
        if (isUnassigned(nodes)) {
            return null;
        }
        return nodes;
    }

    private void appendReason(StringBuilder sb) {
        if (stateReason != null && !stateReason.isBlank() && !"None".equalsIgnoreCase(stateReason)) {
            sb.append(" (").append(stateReason).append(")");
        }
    }

    private static boolean isUnassigned(@CheckForNull String nodes) {
        if (nodes == null || nodes.isBlank()) {
            return true;
        }
        String trimmed = nodes.trim();
        return "none".equalsIgnoreCase(trimmed)
                || "none assigned".equalsIgnoreCase(trimmed)
                || "unassigned".equalsIgnoreCase(trimmed)
                || "n/a".equalsIgnoreCase(trimmed)
                || "(null)".equalsIgnoreCase(trimmed);
    }
}
