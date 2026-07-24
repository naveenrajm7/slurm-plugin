package io.jenkins.plugins.slurm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.jenkins.plugins.slurm.client.model.Job;
import io.jenkins.plugins.slurm.client.model.JobInfo;
import io.jenkins.plugins.slurm.client.model.JobState1;
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

    @Test
    void selectJob_prefersMatchingJobId() {
        JobInfo other = new JobInfo().jobId(11);
        JobInfo target = new JobInfo().jobId(67548129);
        JobInfo selected = SlurmClient.selectJob(java.util.List.of(other, target), "67548129");
        assertEquals(Integer.valueOf(67548129), selected.getJobId());
    }

    @Test
    void selectJob_fallsBackToFirstWhenNoMatch() {
        JobInfo first = new JobInfo().jobId(1);
        JobInfo second = new JobInfo().jobId(2);
        JobInfo selected = SlurmClient.selectJob(java.util.List.of(first, second), "999");
        assertEquals(Integer.valueOf(1), selected.getJobId());
    }

    @Test
    void selectJob_fallsBackToFirstWhenIdNotNumeric() {
        JobInfo first = new JobInfo().jobId(5);
        JobInfo selected = SlurmClient.selectJob(java.util.List.of(first), "s8FXJCCS3F9Z00");
        assertEquals(Integer.valueOf(5), selected.getJobId());
    }

    @Test
    void resolveSlurmdbState_returnsCurrentStateWhenPresent() {
        Job job = new Job().state(new JobState1().current(java.util.List.of(JobState1.CurrentEnum.RUNNING)));
        assertEquals("RUNNING", SlurmClient.resolveSlurmdbState(job));
    }

    @Test
    void resolveSlurmdbState_nullWhenStateMissing() {
        assertNull(SlurmClient.resolveSlurmdbState(new Job()));
        assertNull(SlurmClient.resolveSlurmdbState(new Job().state(new JobState1())));
        assertNull(SlurmClient.resolveSlurmdbState(null));
    }

    @Test
    void selectSlurmdbJob_prefersMatchingJobId() {
        Job other = new Job().jobId(11);
        Job target = new Job().jobId(67588321);
        Job selected = SlurmClient.selectSlurmdbJob(java.util.List.of(other, target), "67588321");
        assertEquals(Integer.valueOf(67588321), selected.getJobId());
    }

    @Test
    void selectSlurmdbJob_prefersLastMatchOnDuplicateIds() {
        Job firstRun = new Job().jobId(42).nodes("node-a");
        Job restart = new Job().jobId(42).nodes("node-b");
        Job selected = SlurmClient.selectSlurmdbJob(java.util.List.of(firstRun, restart), "42");
        assertEquals("node-b", selected.getNodes());
    }

    @Test
    void selectSlurmdbJob_fallsBackToLastWhenNoMatch() {
        Job first = new Job().jobId(1);
        Job second = new Job().jobId(2);
        Job selected = SlurmClient.selectSlurmdbJob(java.util.List.of(first, second), "999");
        assertEquals(Integer.valueOf(2), selected.getJobId());
    }
}
