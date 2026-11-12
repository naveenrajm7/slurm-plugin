package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
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

    @Test
    void attachJobStatus_pendingPlaceholderDoesNotFailTheActivity(JenkinsRule j) throws Exception {
        SlurmCloudStats.clearForTest();
        SlurmAgent agent = SlurmTestHelper.createAgent("pending-agent", "slurm-test", "tpl-1");
        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(agent.getId());
        activity.enter(ProvisioningActivity.Phase.LAUNCHING);
        Set<String> keys = new HashSet<>();
        long startedAt = System.currentTimeMillis() - SlurmCloudStats.PENDING_WARN_AFTER_MS - 5_000;

        SlurmCloudStats.attachJobStatus(
                agent, new SlurmJobStatus("7", "PENDING", "Priority", "None assigned"), startedAt, keys);
        SlurmCloudStats.attachJobStatus(agent, new SlurmJobStatus("7", "RUNNING", null, "nid1"), startedAt, keys);

        String titles = titles(activity, ProvisioningActivity.Phase.LAUNCHING);
        assertTrue(titles.contains("Slurm job 7: PENDING (Priority), no node yet"), titles);
        assertFalse(titles.contains("None assigned"), titles);
        assertTrue(titles.contains("PENDING longer than 60s (Priority)"), titles);
        assertTrue(titles.contains("was PENDING for"), titles);
        assertTrue(titles.contains("RUNNING on nid1"), titles);
        assertEquals(ProvisioningActivity.Status.OK, activity.getStatus());
    }

    @Test
    void statusFor_successIsOkAndAnythingElseIsWarn() {
        assertEquals(ProvisioningActivity.Status.OK, SlurmCloudStats.statusFor(Result.SUCCESS));
        assertEquals(ProvisioningActivity.Status.WARN, SlurmCloudStats.statusFor(Result.FAILURE));
        assertEquals(ProvisioningActivity.Status.WARN, SlurmCloudStats.statusFor(Result.UNSTABLE));
        assertEquals(ProvisioningActivity.Status.WARN, SlurmCloudStats.statusFor(Result.ABORTED));
        assertEquals(ProvisioningActivity.Status.WARN, SlurmCloudStats.statusFor(null));
    }

    @Test
    void formatElapsed_usesHoursAfterAnHour() {
        assertEquals("45s", SlurmCloudStats.formatElapsed(45_000));
        assertEquals("1 hr 7 min", SlurmCloudStats.formatElapsed((67 * 60L) * 1000));
    }

    @Test
    void completedBuild_isNotALaunchFailure(JenkinsRule j) throws Exception {
        SlurmCloudStats.clearForTest();
        FreeStyleProject project = j.createFreeStyleProject("amd-job");
        var build = j.buildAndAssertSuccess(project);
        SlurmAgent agent = SlurmTestHelper.createAgent("ran-agent", "slurm-test", "tpl-1");
        agent.trackProvisioningOwner(new JobUtils.ProvisioningOwner(1L, build));
        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(agent.getId());
        activity.enter(ProvisioningActivity.Phase.LAUNCHING);
        activity.enter(ProvisioningActivity.Phase.OPERATING);

        SlurmCloudStats.recordBuildStarted(agent, build);
        activity.enter(ProvisioningActivity.Phase.COMPLETED);
        SlurmCloudStats.recordAgentRemoved(agent);

        String operating = titles(activity, ProvisioningActivity.Phase.OPERATING);
        String completed = titles(activity, ProvisioningActivity.Phase.COMPLETED);
        assertTrue(operating.contains("Running amd-job #1"), operating);
        assertTrue(operating.contains(build.getUrl()), operating);
        assertTrue(completed.contains("Agent removed"), completed);
        assertTrue(completed.contains("finished SUCCESS"), completed);
        assertFalse(completed.contains("FAIL"), completed);
        assertEquals(ProvisioningActivity.Status.OK, activity.getStatus());

        SlurmCloudStats.recordBuildFinished(build);
        assertEquals(titles(activity, ProvisioningActivity.Phase.COMPLETED), completed);
    }

    private static String titles(ProvisioningActivity activity, ProvisioningActivity.Phase phase) {
        List<PhaseExecutionAttachment> attachments =
                activity.getPhaseExecution(phase).getAttachments();
        return attachments.stream().map(PhaseExecutionAttachment::getTitle).collect(Collectors.joining("\n"));
    }
}
