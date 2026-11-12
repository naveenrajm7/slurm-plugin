package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.TaskListener;
import io.jenkins.plugins.slurm.client.ApiException;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import io.jenkins.plugins.slurm.client.model.JobDescMsg;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A launch that never gets a node must not abort a waiting build. A job that was allocated and
 * then broke fails only the build that agent claimed (issue #49).
 */
@WithJenkins
class SlurmLaunchFailureTest {

    @Test
    void transientSubmit_retriesAndLeavesBothBuildsQueued(JenkinsRule j) throws Exception {
        RecordingCloud cloud = cloud(j, "transient-cloud");
        cloud.submitFailure = new ApiException(new IOException("Invalid status line: \"HTTP/0.9 400 BAD REQUEST\""));
        WaitingBuilds waiting = waitingBuilds(j);
        try {
            SlurmLauncher launcher = fastLauncher();
            SlurmAgent agent = agent(cloud, "transient-agent", launcher);
            agent.trackProvisioningOwner(waiting.ownerA);
            j.jenkins.addNode(agent);

            awaitNodeGone(j, agent.getNodeName());
            assertTrue(cloud.submits.get() >= SlurmLauncher.SUBMIT_ATTEMPTS, "submits=" + cloud.submits.get());
            assertTrue(isStillQueued(waiting.ownerA), "the claimed build must stay queued");
            assertTrue(isStillQueued(waiting.ownerB), "another build on the same label must stay queued");
            assertNotNull(launcher.getProblem());
            String problem = launcher.getProblem().getMessage();
            assertTrue(problem.contains("Slurm submit failed"), problem);
            assertTrue(problem.contains("Invalid status line"), problem);
            assertFalse(problem.contains("retention strategy"), problem);
            assertTrue(cloud.cancelled.isEmpty());
        } finally {
            waiting.close(j);
        }
    }

    @Test
    void permanentSubmitRejection_cancelsOnlyTheClaimedBuild(JenkinsRule j) throws Exception {
        RecordingCloud cloud = cloud(j, "reject-cloud");
        cloud.submitFailure = new ApiException(400, "Invalid partition name");
        WaitingBuilds waiting = waitingBuilds(j);
        try {
            SlurmLauncher launcher = fastLauncher();
            SlurmAgent agent = agent(cloud, "reject-agent", launcher);
            agent.trackProvisioningOwner(waiting.ownerA);
            j.jenkins.addNode(agent);

            awaitNodeGone(j, agent.getNodeName());
            assertTrue(cloud.submits.get() == 1, "a real HTTP 4xx must not be retried, submits=" + cloud.submits.get());
            assertFalse(isStillQueued(waiting.ownerA), "the claimed build should fail");
            assertTrue(isStillQueued(waiting.ownerB), "the other build must keep waiting");
            assertNotNull(launcher.getProblem());
            String problem = launcher.getProblem().getMessage();
            assertTrue(problem.contains("Slurm submit failed"), problem);
            assertTrue(problem.contains("Invalid partition name"), problem);
            assertFalse(problem.contains("retention strategy"), problem);
        } finally {
            waiting.close(j);
        }
    }

    @Test
    void pendingTimeout_cancelsSlurmJobAndLeavesTheBuildQueued(JenkinsRule j) throws Exception {
        RecordingCloud cloud = cloud(j, "pending-cloud");
        cloud.jobId = "424200";
        WaitingBuilds waiting = waitingBuilds(j);
        try {
            SlurmLauncher launcher = fastLauncher();
            launcher.agentTimeoutOverrideMs = 0L;
            SlurmAgent agent = agent(cloud, "pending-agent", launcher);
            agent.trackProvisioningOwner(waiting.ownerA);
            j.jenkins.addNode(agent);

            awaitNodeGone(j, agent.getNodeName());
            assertTrue(cloud.cancelled.contains("424200"), "cancelled=" + cloud.cancelled);
            assertTrue(isStillQueued(waiting.ownerA), "a job that never ran must not fail the build");
            assertTrue(isStillQueued(waiting.ownerB));
            assertNotNull(launcher.getProblem());
            String problem = launcher.getProblem().getMessage();
            assertTrue(problem.contains("never reached RUNNING"), problem);
            assertFalse(problem.contains("retention strategy"), problem);
        } finally {
            waiting.close(j);
        }
    }

    @Test
    void failureAfterRunning_cancelsOnlyTheClaimedBuild(JenkinsRule j) throws Exception {
        RecordingCloud cloud = cloud(j, "running-cloud");
        cloud.jobId = "424201";
        WaitingBuilds waiting = waitingBuilds(j);
        try {
            SlurmLauncher launcher = fastLauncher();
            launcher.agentTimeoutOverrideMs = 30_000L;
            AtomicInteger polls = new AtomicInteger();
            launcher.jobStatusSource = jobId -> {
                if (polls.getAndIncrement() == 0) {
                    return new SlurmJobStatus(jobId, "RUNNING", null, "nid1");
                }
                return new SlurmJobStatus(jobId, "FAILED", "NonZeroExitCode", "nid1");
            };
            SlurmAgent agent = agent(cloud, "running-agent", launcher);
            agent.trackProvisioningOwner(waiting.ownerA);
            j.jenkins.addNode(agent);

            awaitNodeGone(j, agent.getNodeName());
            assertTrue(polls.get() >= 2, "polls=" + polls.get());
            assertTrue(cloud.cancelled.contains("424201"), "cancelled=" + cloud.cancelled);
            assertFalse(isStillQueued(waiting.ownerA), "the build whose job broke should fail");
            assertTrue(isStillQueued(waiting.ownerB), "the other build must keep waiting");
            assertNotNull(launcher.getProblem());
            String problem = launcher.getProblem().getMessage();
            assertTrue(problem.contains("FAILED"), problem);
            assertFalse(problem.contains("retention strategy"), problem);
        } finally {
            waiting.close(j);
        }
    }

    private static SlurmLauncher fastLauncher() {
        SlurmLauncher launcher = new SlurmLauncher();
        launcher.submitBackoffMs = new long[] {0L, 0L, 0L};
        launcher.statusCheckIntervalMs = 0L;
        launcher.connectionPollMs = 0L;
        return launcher;
    }

    private static RecordingCloud cloud(JenkinsRule j, String name) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "not-a-queue-label", 4);
        template.setScript("#!/bin/bash\necho agent\n");
        RecordingCloud cloud = new RecordingCloud(name);
        cloud.setJobTemplates(Collections.singletonList(template));
        j.jenkins.clouds.add(cloud);
        cloud.templateId = template.getId();
        return cloud;
    }

    private static SlurmAgent agent(RecordingCloud cloud, String name, SlurmLauncher launcher) throws Exception {
        return SlurmTestHelper.createAgent(name, cloud.name, cloud.templateId, launcher);
    }

    private static WaitingBuilds waitingBuilds(JenkinsRule j) throws Exception {
        JobUtils.clearProvisioningClaimsForTest();
        j.jenkins.setNumExecutors(4);
        Label label = Label.get("testcloud");
        WorkflowRun first = scheduleWaiting(j, "wait-a-" + System.nanoTime());
        WorkflowRun second = scheduleWaiting(j, "wait-b-" + System.nanoTime());
        JobUtils.ProvisioningOwner ownerA = JobUtils.claimProvisioningOwner(label, "testcloud", "agent-a");
        JobUtils.ProvisioningOwner ownerB = JobUtils.claimProvisioningOwner(label, "testcloud", "agent-b");
        assertNotNull(ownerA);
        assertNotNull(ownerB);
        return new WaitingBuilds(first, second, ownerA, ownerB);
    }

    /** {@link Queue#getItem(long)} also returns a {@link Queue.LeftItem} after cancel. */
    private static boolean isStillQueued(JobUtils.ProvisioningOwner owner) {
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.getId() == owner.queueItemId) {
                return true;
            }
        }
        return false;
    }

    private static void awaitNodeGone(JenkinsRule j, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (j.jenkins.getNode(name) == null) {
                SlurmTestHelper.awaitLaunchThreadsSettled(j.jenkins);
                return;
            }
            Thread.sleep(50);
        }
        fail("agent " + name + " was still registered");
    }

    private static WorkflowRun scheduleWaiting(JenkinsRule j, String name) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("node('testcloud') {\n  echo 'ran'\n}\n", false));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", run);
        return run;
    }

    private static final class WaitingBuilds {
        final WorkflowRun first;
        final WorkflowRun second;
        final JobUtils.ProvisioningOwner ownerA;
        final JobUtils.ProvisioningOwner ownerB;

        WaitingBuilds(
                WorkflowRun first,
                WorkflowRun second,
                JobUtils.ProvisioningOwner ownerA,
                JobUtils.ProvisioningOwner ownerB) {
            this.first = first;
            this.second = second;
            this.ownerA = ownerA;
            this.ownerB = ownerB;
        }

        void close(JenkinsRule j) throws Exception {
            JobUtils.clearProvisioningClaimsForTest();
            abortIfBuilding(j, second);
            abortIfBuilding(j, first);
        }
    }

    private static void abortIfBuilding(JenkinsRule j, WorkflowRun run) throws Exception {
        if (run != null && run.isBuilding() && run.getExecution() != null) {
            run.getExecution().interrupt(Result.ABORTED);
            j.waitForCompletion(run);
        }
    }

    /** Records submit and cancel calls so the test does not need a live slurmrestd. */
    private static final class RecordingCloud extends SlurmCloud {
        final transient AtomicInteger submits = new AtomicInteger();
        final transient List<String> cancelled = Collections.synchronizedList(new ArrayList<>());
        transient Exception submitFailure;
        transient String jobId = "42";
        transient String templateId;

        RecordingCloud(String name) {
            super(name, "http://127.0.0.1:9", null, "compute", 4, 1);
        }

        @Override
        public String submitJob(JobDescMsg jobDesc, TaskListener listener) throws Exception {
            submits.incrementAndGet();
            if (submitFailure != null) {
                throw submitFailure;
            }
            if (listener != null) {
                listener.getLogger().println("Job submitted with ID: " + jobId);
            }
            return jobId;
        }

        @Override
        public void cancelJob(String id, TaskListener listener) {
            cancelled.add(id);
            if (listener != null) {
                listener.getLogger().println("Canceled Slurm job: " + id);
            }
        }
    }
}
