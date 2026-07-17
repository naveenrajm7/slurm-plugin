package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory heartbeat timestamps for Slurm jobs submitted by the plugin.
 *
 * <p>Used by {@link SlurmGarbageCollection} to distinguish live agent jobs from orphans without
 * requiring Slurm-side annotations.
 */
final class SlurmJobHeartbeats {
    private static final ConcurrentMap<String, Entry> BY_JOB_ID = new ConcurrentHashMap<>();

    private SlurmJobHeartbeats() {}

    static void refresh(@NonNull String cloudName, @NonNull String jobId, @NonNull String agentName) {
        BY_JOB_ID.put(jobId, new Entry(cloudName, agentName, System.currentTimeMillis()));
    }

    @CheckForNull
    static Long getLastRefresh(@NonNull String jobId) {
        Entry entry = BY_JOB_ID.get(jobId);
        return entry != null ? entry.lastRefreshMs : null;
    }

    static void remove(@NonNull String jobId) {
        BY_JOB_ID.remove(jobId);
    }

    static void clearForTest() {
        BY_JOB_ID.clear();
    }

    static final class Entry {
        final String cloudName;
        final String agentName;
        final long lastRefreshMs;

        Entry(String cloudName, String agentName, long lastRefreshMs) {
            this.cloudName = cloudName;
            this.agentName = agentName;
            this.lastRefreshMs = lastRefreshMs;
        }
    }
}
