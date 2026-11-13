package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;

/** The registry is not touched when Jenkins is not running, which is how a missing plugin behaves. */
class SlurmMetricsSoftDependencyTest {

    @Test
    void absentJenkins_recordsNothingAndDoesNotThrow() {
        assumeTrue(Jenkins.getInstanceOrNull() == null, "another test still has Jenkins running");
        SlurmMetrics.jobSubmitted("1");
        SlurmMetrics.agentConnected("1");
        SlurmMetrics.launchFailed("1");
        SlurmMetrics.launchTimedOut();
        SlurmMetrics.jobCancelled("1");
        SlurmMetrics.jobCancelled("1");
        SlurmMetrics.discard("1");
    }
}
