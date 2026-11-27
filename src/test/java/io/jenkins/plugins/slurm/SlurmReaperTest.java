package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.slurm.client.ApiException;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Stuck agents are removed, and only a connected or still-launching agent protects its Slurm job. */
@WithJenkins
class SlurmReaperTest {

    @AfterEach
    void clearReaperState() {
        SlurmReaper.clearForTest();
        SlurmJobHeartbeats.clearForTest();
    }

    @Test
    void offlineAgentWithRunningJob_isRemovedAndCancelledAfterGrace(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-running");
        cloud.status = new SlurmJobStatus("55", "RUNNING", null, "nid1");
        SlurmAgent agent = agent(cloud, "stale-running");
        agent.setSlurmJobId("55");
        j.jenkins.addNode(agent);
        agent.toComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(null, "channel gone"));

        long now = 1_000_000L;
        SlurmReaper.reap(now, 60_000);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()), "inside the grace period the agent stays");
        assertTrue(cloud.cancelled.isEmpty());

        SlurmReaper.reap(now + 60_000, 60_000);
        assertNull(j.jenkins.getNode(agent.getNodeName()));
        assertEquals(List.of("55"), cloud.cancelled);
    }

    @Test
    void connectedAgentOfflineForDiskSpace_keepsRunningBuildAndJob(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-disk-space");
        cloud.status = new SlurmJobStatus("61", "RUNNING", null, "nid1");
        SlurmAgent agent = connectedAgent(j, cloud, "disk-space-busy", "61");
        WorkflowRun build = startBuildOn(j, agent);
        SlurmComputer computer = (SlurmComputer) agent.toComputer();

        computer.setTemporarilyOffline(true, new DiskSpaceMonitorDescriptor.DiskSpace("/tmp", 1L));
        assertTrue(computer.isOffline());

        long now = 1_000_000L;
        SlurmReaper.reap(now, 60_000);
        SlurmReaper.reap(now + 600_000, 60_000);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()), "a temporarily offline agent is still connected");
        assertTrue(cloud.cancelled.isEmpty(), "disk space must not cancel the running job");
        assertTrue(build.isBuilding());
        assertTrue(SlurmGarbageCollection.PeriodicGarbageCollection.protectsJob(agent));

        j.assertBuildStatusSuccess(j.waitForCompletion(build));
    }

    @Test
    void connectedIdleAgentOfflineForDiskSpace_isNotReaped(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-disk-space-idle");
        cloud.status = new SlurmJobStatus("62", "RUNNING", null, "nid1");
        SlurmAgent agent = connectedAgent(j, cloud, "disk-space-idle", "62");
        agent.toComputer().setTemporarilyOffline(true, new DiskSpaceMonitorDescriptor.DiskSpace("/tmp", 1L));

        SlurmReaper.reap(1_000_000L, 60_000);
        SlurmReaper.reap(1_600_000L, 60_000);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()), "retention, not the reaper, ends a connected agent");
        assertTrue(cloud.cancelled.isEmpty());
        assertTrue(SlurmGarbageCollection.PeriodicGarbageCollection.liveJobIdsForCloud(cloud.name)
                .contains("62"));
    }

    @Test
    void agentWhoseChannelDropped_isStillReapedAfterGrace(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-disconnected");
        cloud.status = new SlurmJobStatus("63", "RUNNING", null, "nid1");
        SlurmAgent agent = connectedAgent(j, cloud, "channel-dropped", "63");
        WorkflowRun build = startBuildOn(j, agent);
        SlurmComputer computer = (SlurmComputer) agent.toComputer();
        computer.setTemporarilyOffline(true, new DiskSpaceMonitorDescriptor.DiskSpace("/tmp", 1L));

        computer.disconnect(new OfflineCause.ChannelTermination(new java.io.IOException("agent JVM gone")))
                .get(30, TimeUnit.SECONDS);
        j.waitForCompletion(build);
        assertFalse(SlurmReaper.isConnected(computer));
        assertTrue(computer.isIdle());
        assertFalse(SlurmGarbageCollection.PeriodicGarbageCollection.protectsJob(agent));

        long now = 1_000_000L;
        SlurmReaper.reap(now, 60_000);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()), "inside the grace period the agent stays");
        SlurmReaper.reap(now + 60_000, 60_000);
        assertNull(j.jenkins.getNode(agent.getNodeName()), "a dead agent must still release its slot");
        assertEquals(List.of("63"), cloud.cancelled);
    }

    @Test
    void offlineAgentWithPendingJob_isKeptAndNotCancelled(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-pending");
        cloud.status = new SlurmJobStatus("58", "PENDING", "JobHeldUser", null);
        SlurmAgent agent = agent(cloud, "still-queued");
        agent.setSlurmJobId("58");
        j.jenkins.addNode(agent);
        agent.toComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(null, "waiting for a node"));

        long now = 1_000_000L;
        SlurmReaper.reap(now, 60_000);
        SlurmReaper.reap(now + 120_000, 60_000);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()));
        assertTrue(cloud.cancelled.isEmpty(), "a PENDING job is waiting for a machine");
    }

    @Test
    void failedLaunchWithPendingJob_isRemovedAfterGraceWithoutCancel(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-failed-pending");
        cloud.status = new SlurmJobStatus("59", "PENDING", "JobHeldUser", null);
        SlurmAgent agent = agent(cloud, "abandoned-queue");
        agent.setSlurmJobId("59");
        j.jenkins.addNode(agent);
        ((SlurmLauncher) ((SlaveComputer) agent.toComputer()).getLauncher())
                .setProblem(new java.io.IOException("cancel failed"));

        long now = 1_000_000L;
        SlurmReaper.reap(now, 60_000);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()), "inside the grace period the agent stays");
        assertTrue(cloud.cancelled.isEmpty());

        SlurmReaper.reap(now + 60_000, 60_000);
        assertNull(j.jenkins.getNode(agent.getNodeName()), "a permanently failed launch must release its slot");
        assertTrue(cloud.cancelled.isEmpty(), "a PENDING job is not cancelled");
    }

    @Test
    void runningJob_keepJobOnFailure_isRemovedWithoutCancel(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-keep");
        cloud.getTemplateById(cloud.templateId).setKeepJobOnFailure(true);
        cloud.status = new SlurmJobStatus("60", "RUNNING", null, "nid1");
        SlurmAgent agent = agent(cloud, "debug-running");
        agent.setSlurmJobId("60");
        j.jenkins.addNode(agent);
        agent.toComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(null, "channel gone"));

        long now = 1_000_000L;
        SlurmReaper.reap(now, 60_000);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()));

        SlurmReaper.reap(now + 60_000, 60_000);
        assertNull(j.jenkins.getNode(agent.getNodeName()));
        assertTrue(cloud.cancelled.isEmpty(), "keepJobOnFailure leaves the allocation running");
    }

    @Test
    void launchingAgent_isLeftAlone(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-launching");
        cloud.status = new SlurmJobStatus("56", "RUNNING", null, "nid1");
        SlurmAgent agent = agent(cloud, "still-launching");
        agent.setSlurmJobId("56");
        j.jenkins.addNode(agent);
        ((SlurmComputer) agent.toComputer()).setLaunching(true);

        SlurmReaper.reap(1_000_000L, 0);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()));
        assertTrue(cloud.cancelled.isEmpty());
    }

    @Test
    void offlineAgentWithoutJob_isRemovedImmediately(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-nojob");
        SlurmAgent agent = agent(cloud, "never-submitted");
        j.jenkins.addNode(agent);
        agent.toComputer()
                .setTemporarilyOffline(true, new OfflineCause.UserCause(null, "Slurm submit failed: status line"));

        SlurmReaper.reap(1_000_000L, 60_000);
        assertNull(j.jenkins.getNode(agent.getNodeName()));
        assertTrue(cloud.cancelled.isEmpty());
    }

    @Test
    void freshAgent_isNotReaped(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-fresh");
        SlurmAgent agent = agent(cloud, "not-launched");
        j.jenkins.addNode(agent);

        SlurmReaper.reap(1_000_000L, 0);
        assertNotNull(j.jenkins.getNode(agent.getNodeName()));
    }

    @Test
    void terminalJob_isRemovedWithoutAnotherCancel(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "reap-terminal");
        cloud.status = new SlurmJobStatus("57", "FAILED", "NonZeroExitCode", "nid1");
        SlurmAgent agent = agent(cloud, "already-failed");
        agent.setSlurmJobId("57");
        j.jenkins.addNode(agent);
        agent.toComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(null, "job failed"));

        SlurmReaper.reap(1_000_000L, 60_000);
        assertNull(j.jenkins.getNode(agent.getNodeName()));
        assertTrue(cloud.cancelled.isEmpty(), "a terminal job is not signalled again");
    }

    @Test
    void liveJobIds_skipOfflineAgentsAndKeepConnectingOnes(JenkinsRule j) throws Exception {
        StatusCloud cloud = cloud(j, "gc-live");
        SlurmAgent offline = agent(cloud, "gc-offline");
        offline.setSlurmJobId("70");
        SlurmAgent launching = agent(cloud, "gc-launching");
        launching.setSlurmJobId("71");
        j.jenkins.addNode(offline);
        j.jenkins.addNode(launching);
        ((SlurmComputer) launching.toComputer()).setLaunching(true);

        SlurmJobHeartbeats.refresh(cloud.name, "70", offline.getNodeName());
        SlurmJobHeartbeats.refresh(cloud.name, "71", launching.getNodeName());
        long later = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
        Set<String> live = SlurmGarbageCollection.PeriodicGarbageCollection.liveJobIdsForCloud(cloud.name);

        assertFalse(live.contains("70"), "an offline computer must not protect its job");
        assertTrue(live.contains("71"), "an agent still inside waitForAgentConnection protects its job");
        assertFalse(SlurmGarbageCollection.PeriodicGarbageCollection.protectsJob(offline));
        assertTrue(SlurmGarbageCollection.PeriodicGarbageCollection.protectsJob(launching));
        assertTrue(
                SlurmGarbageCollection.shouldCancelOrphan("70", live, Duration.ofMinutes(5), later),
                "GC cancels a stale job whose agent is offline");
        assertFalse(
                SlurmGarbageCollection.shouldCancelOrphan("71", live, Duration.ofMinutes(5), later),
                "GC does not cancel a job whose agent is still connecting");

        ((SlurmComputer) launching.toComputer()).setLaunching(false);
        // isOnline() is the other half of protectsJob. This computer has no channel, so it is offline.
        assertFalse(((SlurmComputer) launching.toComputer()).isOnline());
        assertFalse(SlurmGarbageCollection.PeriodicGarbageCollection.liveJobIdsForCloud(cloud.name)
                .contains("71"));
    }

    private static StatusCloud cloud(JenkinsRule j, String name) {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "not-a-queue-label", 4);
        StatusCloud cloud = new StatusCloud(name);
        cloud.setJobTemplates(Collections.singletonList(template));
        j.jenkins.clouds.add(cloud);
        cloud.templateId = template.getId();
        return cloud;
    }

    /** A Slurm agent with a real remoting channel, as after JNLP connect. */
    private static SlurmAgent connectedAgent(JenkinsRule j, StatusCloud cloud, String name, String jobId)
            throws Exception {
        SlurmAgent agent = agent(cloud, name, j.createComputerLauncher(null));
        agent.setSlurmJobId(jobId);
        j.jenkins.addNode(agent);
        agent.toComputer().connect(false).get(60, TimeUnit.SECONDS);
        j.waitOnline(agent);
        return agent;
    }

    private static WorkflowRun startBuildOn(JenkinsRule j, SlurmAgent agent) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "busy-" + agent.getNodeName());
        job.setDefinition(new CpsFlowDefinition("node('" + agent.getNodeName() + "') { sleep 20 }", true));
        WorkflowRun build = job.scheduleBuild2(0).waitForStart();
        long deadline = System.currentTimeMillis() + 60_000;
        while (agent.toComputer().isIdle() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertFalse(agent.toComputer().isIdle(), "the build should be on the agent");
        return build;
    }

    private static SlurmAgent agent(StatusCloud cloud, String name) throws Exception {
        return agent(cloud, name, new SlurmLauncher());
    }

    private static SlurmAgent agent(StatusCloud cloud, String name, ComputerLauncher launcher) throws Exception {
        return new SlurmAgent(
                name,
                "test agent",
                "/tmp",
                1,
                Node.Mode.NORMAL,
                "linux",
                launcher,
                SlurmTestHelper.noConnect(),
                Collections.emptyList(),
                cloud.name,
                cloud.templateId,
                "compute",
                new ProvisioningActivity.Id(cloud.name, "template", name));
    }

    private static final class StatusCloud extends SlurmCloud {
        final transient List<String> cancelled = Collections.synchronizedList(new ArrayList<>());
        transient SlurmJobStatus status;
        transient String templateId;

        StatusCloud(String name) {
            super(name, "http://127.0.0.1:9", null, "compute", 4, 1);
        }

        @Override
        SlurmJobStatus fetchJobStatus(String jobId) throws ApiException {
            return status;
        }

        @Override
        public void cancelJob(String jobId, TaskListener listener) {
            cancelled.add(jobId);
        }
    }
}
