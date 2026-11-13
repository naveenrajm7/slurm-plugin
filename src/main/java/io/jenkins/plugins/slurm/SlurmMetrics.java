package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Records provisioning counters when the Metrics plugin is installed.
 *
 * <p>The dependency is optional. Metric updates return immediately when that plugin is absent, and
 * a failure inside the registry does not fail the launch. The Prometheus plugin, when installed,
 * scrapes the same registry; this class does not depend on it.
 *
 * <p>The Metrics API is referenced only from methods that run after the plugin check, so the
 * controller can load this plugin without the Metrics plugin.
 */
final class SlurmMetrics {

    private static final Logger LOGGER = Logger.getLogger(SlurmMetrics.class.getName());

    /** Recent job ids already counted, so a launch cancel and the later terminate count once. */
    private static final int MAX_RECENT_CANCELS = 4096;

    private static final ConcurrentHashMap<String, Long> SUBMITTED_AT_NANOS = new ConcurrentHashMap<>();
    private static final Set<String> RECENT_CANCELS = ConcurrentHashMap.newKeySet();
    private static final Deque<String> CANCEL_ORDER = new ArrayDeque<>();

    private SlurmMetrics() {}

    static void jobSubmitted(@CheckForNull String jobId) {
        if (!enabled()) {
            return;
        }
        if (jobId != null) {
            SUBMITTED_AT_NANOS.put(jobId, System.nanoTime());
        }
        increment(SlurmMetricNames.JOBS_SUBMITTED);
    }

    static void agentConnected(@CheckForNull String jobId) {
        if (!enabled()) {
            return;
        }
        Long started = jobId == null ? null : SUBMITTED_AT_NANOS.remove(jobId);
        if (started != null) {
            recordDuration(System.nanoTime() - started);
        }
        increment(SlurmMetricNames.AGENTS_CONNECTED);
    }

    static void launchFailed(@CheckForNull String jobId) {
        discard(jobId);
        increment(SlurmMetricNames.LAUNCH_FAILED);
    }

    static void launchTimedOut() {
        increment(SlurmMetricNames.LAUNCH_FAILED_TIMEOUT);
    }

    /**
     * Counts a cancel once per job id. A failed launch cancels from the launcher and again from
     * agent termination; both calls name the same job.
     */
    static void jobCancelled(@CheckForNull String jobId) {
        if (jobId == null || jobId.isEmpty() || !enabled()) {
            return;
        }
        if (!rememberCancel(jobId)) {
            return;
        }
        increment(SlurmMetricNames.JOBS_CANCELLED);
    }

    static void discard(@CheckForNull String jobId) {
        if (jobId != null) {
            SUBMITTED_AT_NANOS.remove(jobId);
        }
    }

    static long count(String name) {
        if (!enabled()) {
            return 0;
        }
        try {
            return jenkins.metrics.api.Metrics.metricRegistry().counter(name).getCount();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "Unable to read " + name, e);
            return 0;
        }
    }

    static long launchDurationCount() {
        if (!enabled()) {
            return 0;
        }
        try {
            return jenkins.metrics.api.Metrics.metricRegistry()
                    .timer(SlurmMetricNames.LAUNCH_DURATION)
                    .getCount();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "Unable to read launch duration", e);
            return 0;
        }
    }

    static void clearForTest() {
        SUBMITTED_AT_NANOS.clear();
        synchronized (RECENT_CANCELS) {
            RECENT_CANCELS.clear();
            CANCEL_ORDER.clear();
        }
    }

    private static boolean rememberCancel(String jobId) {
        synchronized (RECENT_CANCELS) {
            if (!RECENT_CANCELS.add(jobId)) {
                return false;
            }
            CANCEL_ORDER.addLast(jobId);
            while (CANCEL_ORDER.size() > MAX_RECENT_CANCELS) {
                RECENT_CANCELS.remove(CANCEL_ORDER.removeFirst());
            }
            return true;
        }
    }

    private static boolean enabled() {
        try {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            return jenkins != null && jenkins.getPlugin("metrics") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void increment(String name) {
        if (!enabled()) {
            return;
        }
        try {
            jenkins.metrics.api.Metrics.metricRegistry().counter(name).inc();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "Unable to record " + name, e);
        }
    }

    private static void recordDuration(long elapsedNanos) {
        if (!enabled()) {
            return;
        }
        try {
            jenkins.metrics.api.Metrics.metricRegistry()
                    .timer(SlurmMetricNames.LAUNCH_DURATION)
                    .update(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "Unable to record launch duration", e);
        }
    }
}
