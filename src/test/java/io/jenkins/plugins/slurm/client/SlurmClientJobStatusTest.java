package io.jenkins.plugins.slurm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.jenkins.plugins.slurm.client.model.JobInfo;
import org.junit.jupiter.api.Test;

class SlurmClientJobStatusTest {

    @Test
    void resolveJobState_returnsFirstStateWhenPresent() {
        JobInfo jobInfo = new JobInfo().jobState(java.util.List.of(JobInfo.JobStateEnum.RUNNING));
        assertEquals("RUNNING", SlurmClient.resolveJobState(jobInfo));
    }

    @Test
    void resolveJobState_defaultsToPendingWhenEmpty() {
        JobInfo jobInfo = new JobInfo().jobState(java.util.Collections.emptyList());
        assertEquals("PENDING", SlurmClient.resolveJobState(jobInfo));
    }

    @Test
    void resolveJobState_defaultsToPendingWhenNull() {
        assertEquals("PENDING", SlurmClient.resolveJobState(new JobInfo()));
    }
}
