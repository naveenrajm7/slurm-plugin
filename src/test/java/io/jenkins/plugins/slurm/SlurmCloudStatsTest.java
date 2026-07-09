package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import java.util.HashSet;
import java.util.Set;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlurmCloudStatsTest {

    @Test
    void attachmentLevelForStatus_marksFailedStatesAsFail() {
        assertEquals(
                ProvisioningActivity.Status.FAIL,
                SlurmCloudStats.attachmentLevelForStatus(new SlurmJobStatus("1", "FAILED", null, null)));
        assertEquals(
                ProvisioningActivity.Status.FAIL,
                SlurmCloudStats.attachmentLevelForStatus(new SlurmJobStatus("1", "CANCELLED", null, null)));
    }

    @Test
    void attachmentLevelForStatus_marksRunningAsOk() {
        assertEquals(
                ProvisioningActivity.Status.OK,
                SlurmCloudStats.attachmentLevelForStatus(new SlurmJobStatus("1", "RUNNING", null, "node1")));
    }

    @Test
    void isLongPending_trueAfterThreshold() {
        long startedAt = System.currentTimeMillis() - SlurmCloudStats.PENDING_WARN_AFTER_MS - 1;
        SlurmJobStatus pending = new SlurmJobStatus("42", "PENDING", "Priority", null);
        assertTrue(SlurmCloudStats.isLongPending(pending, startedAt));
    }

    @Test
    void isLongPending_falseForRunning() {
        long startedAt = System.currentTimeMillis() - SlurmCloudStats.PENDING_WARN_AFTER_MS - 1;
        SlurmJobStatus running = new SlurmJobStatus("42", "RUNNING", null, null);
        assertFalse(SlurmCloudStats.isLongPending(running, startedAt));
    }

    @Test
    void formatFailureTitle_includesJobIdAndState() {
        String title = SlurmCloudStats.formatFailureTitle("413835", "FAILED", "Launch failed");
        assertEquals("Launch failed (job 413835, state FAILED)", title);
    }

    @Test
    void attachJobStatus_deduplicatesIdenticalDisplayLines(JenkinsRule j) throws Exception {
        Set<String> keys = new HashSet<>();
        SlurmAgent agent = SlurmTestHelper.createAgent("agent-1", "slurm-test", "tpl-1");
        j.jenkins.addNode(agent);
        SlurmJobStatus status = new SlurmJobStatus("1", "PENDING", "Priority", null);
        long startedAt = System.currentTimeMillis();

        SlurmCloudStats.attachJobStatus(agent, status, startedAt, keys);
        int sizeAfterFirst = keys.size();
        SlurmCloudStats.attachJobStatus(agent, status, startedAt, keys);

        assertTrue(sizeAfterFirst > 0);
        assertEquals(sizeAfterFirst, keys.size());
    }
}
