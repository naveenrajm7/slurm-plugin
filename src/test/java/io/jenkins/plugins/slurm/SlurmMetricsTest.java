package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Counters move when the Metrics plugin is installed, and a repeated cancel counts once. */
@WithJenkins
class SlurmMetricsTest {

    @Test
    void recordsSubmitConnectFailureAndASingleCancel(JenkinsRule j) {
        assertNotNull(j.jenkins.getPlugin("metrics"), "metrics plugin should load in this test");
        SlurmMetrics.clearForTest();

        long submitted = SlurmMetrics.count(SlurmMetricNames.JOBS_SUBMITTED);
        long connected = SlurmMetrics.count(SlurmMetricNames.AGENTS_CONNECTED);
        long failed = SlurmMetrics.count(SlurmMetricNames.LAUNCH_FAILED);
        long timedOut = SlurmMetrics.count(SlurmMetricNames.LAUNCH_FAILED_TIMEOUT);
        long cancelled = SlurmMetrics.count(SlurmMetricNames.JOBS_CANCELLED);
        long durations = SlurmMetrics.launchDurationCount();

        SlurmMetrics.jobSubmitted("metric-connect");
        SlurmMetrics.agentConnected("metric-connect");
        assertEquals(submitted + 1, SlurmMetrics.count(SlurmMetricNames.JOBS_SUBMITTED));
        assertEquals(connected + 1, SlurmMetrics.count(SlurmMetricNames.AGENTS_CONNECTED));
        assertEquals(durations + 1, SlurmMetrics.launchDurationCount());

        SlurmMetrics.jobSubmitted("metric-fail");
        SlurmMetrics.launchFailed("metric-fail");
        SlurmMetrics.launchTimedOut();
        SlurmMetrics.agentConnected("metric-fail");
        assertEquals(failed + 1, SlurmMetrics.count(SlurmMetricNames.LAUNCH_FAILED));
        assertEquals(timedOut + 1, SlurmMetrics.count(SlurmMetricNames.LAUNCH_FAILED_TIMEOUT));
        assertEquals(durations + 1, SlurmMetrics.launchDurationCount(), "a failed launch does not record duration");

        SlurmMetrics.jobCancelled("metric-cancel");
        SlurmMetrics.jobCancelled("metric-cancel");
        assertEquals(cancelled + 1, SlurmMetrics.count(SlurmMetricNames.JOBS_CANCELLED));
    }
}
