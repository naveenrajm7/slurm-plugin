package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.slaves.DumbSlave;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Aborting a build while its agent is still provisioning must drop that agent only.
 * The next build has to get a new node and a new Slurm job (issue #45).
 */
@WithJenkins
class SlurmProvisioningAbortTest {

    @Test
    void abortingOneBuild_releasesOnlyThatAgentsClaim(JenkinsRule j) throws Exception {
        JobUtils.clearProvisioningClaimsForTest();
        j.jenkins.setNumExecutors(4);
        Label label = Label.get("testcloud");
        WorkflowRun first = null;
        WorkflowRun second = null;
        try {
            assertNull(JobUtils.claimProvisioningOwner(label, "testcloud", "nobody"));

            first = scheduleWaiting(j, "smoke-a");
            second = scheduleWaiting(j, "smoke-b");

            JobUtils.ProvisioningOwner ownerA = JobUtils.claimProvisioningOwner(label, "testcloud", "agent-a");
            JobUtils.ProvisioningOwner ownerB = JobUtils.claimProvisioningOwner(label, "testcloud", "agent-b");
            assertNotNull(ownerA);
            assertNotNull(ownerB);
            assertNotEquals(ownerA.queueItemId, ownerB.queueItemId);
            assertNotNull(ownerA.run);
            assertNotNull(ownerB.run);
            assertNotEquals(ownerA.run.getExternalizableId(), ownerB.run.getExternalizableId());

            SlurmAgent agentA = SlurmTestHelper.createAgent("agent-a", "abort-cloud", "template");
            agentA.trackProvisioningOwner(ownerA);
            assertFalse(SlurmLauncher.shouldStopWaitingForBuild(agentA));
            Queue.Item live = j.jenkins.getQueue().getItem(ownerA.queueItemId);
            assertNotNull(live);
            assertEquals(JobUtils.ProvisioningRelease.WAITING, JobUtils.classifyProvisioningRelease(live, ownerA.run));
            if (live.task instanceof Queue.Executable executable) {
                assertEquals(ownerA.run, SlurmCloudStats.owningRun(executable));
            }
            Queue.LeftItem cancelled = new Queue.LeftItem(live);
            assertTrue(cancelled.isCancelled());
            assertEquals(
                    JobUtils.ProvisioningRelease.BUILD_FINISHED,
                    JobUtils.classifyProvisioningRelease(cancelled, ownerA.run));
            assertFalse(JobUtils.provisioningOwnerReleased(ownerB.queueItemId, ownerB.run));

            Thread.sleep(1500);
            assertFalse(SlurmLauncher.shouldStopWaitingForBuild(agentA), "a waiting build must keep its agent");

            WorkflowRun aborted = (WorkflowRun) ownerA.run;
            aborted.getExecution().interrupt(Result.ABORTED);
            j.waitForCompletion(aborted);
            awaitReleased(ownerA);

            assertTrue(SlurmLauncher.shouldStopWaitingForBuild(agentA));
            assertFalse(
                    JobUtils.provisioningOwnerReleased(ownerB.queueItemId, ownerB.run),
                    "the other build is still waiting and must keep its claim");
        } finally {
            JobUtils.clearProvisioningClaimsForTest();
            abortIfBuilding(j, second);
            abortIfBuilding(j, first);
        }
    }

    @Test
    void unstableResultWhileStillBuilding_keepsTheClaim(JenkinsRule j) throws Exception {
        JobUtils.clearProvisioningClaimsForTest();
        j.jenkins.setNumExecutors(4);
        WorkflowRun run = null;
        try {
            run = scheduleWaiting(j, "smoke-unstable");
            JobUtils.ProvisioningOwner owner =
                    JobUtils.claimProvisioningOwner(Label.get("testcloud"), "testcloud", "agent-u");
            assertNotNull(owner);
            assertNotNull(owner.run);
            owner.run.setResult(Result.UNSTABLE);
            assertTrue(owner.run.isBuilding());
            assertNotNull(owner.run.getResult());
            assertFalse(
                    JobUtils.provisioningOwnerReleased(owner.queueItemId, owner.run),
                    "an early UNSTABLE result must not cancel the agent still being provisioned");

            SlurmAgent agent = SlurmTestHelper.createAgent("agent-u", "abort-cloud", "template");
            agent.trackProvisioningOwner(owner);
            assertFalse(SlurmLauncher.shouldStopWaitingForBuild(agent));
        } finally {
            JobUtils.clearProvisioningClaimsForTest();
            abortIfBuilding(j, run);
        }
    }

    @Test
    void stopForFinishedBuild_cancelsSlurmJobAndDeletesNode(JenkinsRule j) throws Exception {
        RecordingCloud cloud = new RecordingCloud();
        j.jenkins.clouds.add(cloud);
        SlurmAgent agent = SlurmTestHelper.createStaticAgent("abort-agent", cloud.name, "template");
        agent.setSlurmJobId("68298173");
        assertFalse(SlurmLauncher.shouldStopWaitingForBuild(agent));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TaskListener listener = new StreamTaskListener(output);
        agent.setRunListener(listener);
        j.jenkins.addNode(agent);

        SlurmComputer computer = (SlurmComputer) agent.toComputer();
        assertNotNull(computer);
        long failed = SlurmMetrics.count(SlurmMetricNames.LAUNCH_FAILED);
        long cancelled = SlurmMetrics.count(SlurmMetricNames.JOBS_CANCELLED);
        SlurmLauncher launcher = new SlurmLauncher();
        SlurmLauncher.ProvisioningOwnerGoneException failure = assertThrows(
                SlurmLauncher.ProvisioningOwnerGoneException.class,
                () -> launcher.stopForFinishedBuild(computer, agent, cloud, "68298173", listener));

        assertTrue(failure.getMessage().contains("abort-agent"));
        assertTrue(failure.getMessage().contains("Build finished"), failure.getMessage());
        assertTrue(cloud.cancelled.contains("68298173"));
        assertNull(j.jenkins.getNode("abort-agent"));
        String log = output.toString();
        assertTrue(log.contains("[Slurm] Agent provisioning interrupted"), log);
        assertTrue(log.contains("Canceled Slurm job: 68298173"), log);
        assertEquals(failed + 1, SlurmMetrics.count(SlurmMetricNames.LAUNCH_FAILED));
        assertEquals(cancelled + 1, SlurmMetrics.count(SlurmMetricNames.JOBS_CANCELLED));
        SlurmTestHelper.awaitLaunchThreadsSettled(j.jenkins);
    }

    @Test
    void queueItemTakenByStaticAgent_isNotALaunchFailure(JenkinsRule j) throws Exception {
        JobUtils.clearProvisioningClaimsForTest();
        SlurmCloudStats.clearForTest();
        j.jenkins.setNumExecutors(4);
        RecordingCloud cloud = new RecordingCloud();
        j.jenkins.clouds.add(cloud);
        WorkflowRun run = null;
        try {
            run = scheduleSleeping(j, "handoff");
            Label label = Label.get("handoff-label");
            JobUtils.ProvisioningOwner owner = JobUtils.claimProvisioningOwner(label, "handoff-label", "spare-agent");
            assertNotNull(owner);
            assertNotNull(owner.run);
            assertTrue(owner.run.isBuilding());

            DumbSlave staticAgent = j.createOnlineSlave(label);
            assertNotNull(staticAgent);
            awaitTaken(owner);
            assertTrue(owner.run.isBuilding());

            SlurmAgent agent = SlurmTestHelper.createStaticAgent("spare-agent", cloud.name, "template");
            agent.setSlurmJobId("68355353");
            agent.trackProvisioningOwner(owner);
            ProvisioningActivity activity =
                    CloudStatistics.ProvisioningListener.get().onStarted(agent.getId());
            activity.enter(ProvisioningActivity.Phase.LAUNCHING);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            TaskListener listener = new StreamTaskListener(output);
            agent.setRunListener(listener);
            j.jenkins.addNode(agent);
            long failed = SlurmMetrics.count(SlurmMetricNames.LAUNCH_FAILED);
            long cancelled = SlurmMetrics.count(SlurmMetricNames.JOBS_CANCELLED);
            SlurmComputer computer = (SlurmComputer) agent.toComputer();
            assertNotNull(computer);

            SlurmLauncher launcher = new SlurmLauncher();
            SlurmLauncher.ProvisioningOwnerGoneException failure = assertThrows(
                    SlurmLauncher.ProvisioningOwnerGoneException.class,
                    () -> launcher.stopForFinishedBuild(computer, agent, cloud, "68355353", listener));

            assertTrue(failure.getMessage().contains("taken by another executor"), failure.getMessage());
            assertFalse(failure.getMessage().contains("Build finished"), failure.getMessage());
            String log = output.toString();
            assertTrue(log.contains("Queue item taken by another executor; cancelling Slurm job 68355353"), log);
            assertFalse(log.contains("Build finished"), log);
            assertFalse(log.contains("ERROR:"), log);
            assertTrue(cloud.cancelled.contains("68355353"));
            assertNull(j.jenkins.getNode("spare-agent"));
            assertEquals(ProvisioningActivity.Status.OK, activity.getStatus());
            String titles = activity.getPhaseExecution(ProvisioningActivity.Phase.LAUNCHING).getAttachments().stream()
                    .map(PhaseExecutionAttachment::getTitle)
                    .collect(Collectors.joining("\n"));
            assertTrue(titles.contains("Queue item taken by another executor; cancelling Slurm job 68355353"), titles);
            assertTrue(titles.contains("Slurm job 68355353 cancelled"), titles);
            assertFalse(titles.contains("Build finished"), titles);
            assertEquals(failed, SlurmMetrics.count(SlurmMetricNames.LAUNCH_FAILED));
            assertEquals(cancelled + 1, SlurmMetrics.count(SlurmMetricNames.JOBS_CANCELLED));
        } finally {
            JobUtils.clearProvisioningClaimsForTest();
            abortIfBuilding(j, run);
            SlurmTestHelper.awaitLaunchThreadsSettled(j.jenkins);
        }
    }

    private static WorkflowRun scheduleSleeping(JenkinsRule j, String name) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(
                new CpsFlowDefinition("node('handoff-label') {\n  echo 'on static'\n  sleep(30)\n}\n", false));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", run);
        return run;
    }

    private static void awaitTaken(JobUtils.ProvisioningOwner owner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        while (System.currentTimeMillis() < deadline) {
            if (JobUtils.classifyProvisioningRelease(owner.queueItemId, owner.run)
                    == JobUtils.ProvisioningRelease.TAKEN_BY_ANOTHER_EXECUTOR) {
                return;
            }
            Thread.sleep(100);
        }
        fail("queue item " + owner.queueItemId + " was not taken by another executor (release="
                + JobUtils.classifyProvisioningRelease(owner.queueItemId, owner.run) + ", building="
                + owner.run.isBuilding() + ")");
    }

    private static WorkflowRun scheduleWaiting(JenkinsRule j, String name) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("node('testcloud') {\n  echo 'ran'\n}\n", false));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", run);
        return run;
    }

    private static void awaitReleased(JobUtils.ProvisioningOwner owner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            if (JobUtils.provisioningOwnerReleased(owner.queueItemId, owner.run)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("queue item " + owner.queueItemId + " was still waiting after the build was aborted");
    }

    private static void abortIfBuilding(JenkinsRule j, WorkflowRun run) throws Exception {
        if (run != null && run.isBuilding() && run.getExecution() != null) {
            run.getExecution().interrupt(Result.ABORTED);
            j.waitForCompletion(run);
        }
    }

    /** Records cancel calls so the test does not need a live slurmrestd. */
    private static final class RecordingCloud extends SlurmCloud {
        final List<String> cancelled = Collections.synchronizedList(new ArrayList<>());

        RecordingCloud() {
            super("abort-cloud", "http://127.0.0.1:9", null, "compute", 4, 1);
        }

        @Override
        public void cancelJob(String jobId, TaskListener listener) {
            cancelled.add(jobId);
            if (listener != null) {
                listener.getLogger().println("Canceled Slurm job: " + jobId);
            }
        }
    }
}
