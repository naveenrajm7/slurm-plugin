package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.slurm.client.ApiException;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
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
        assertFalse(
                SlurmGarbageCollection.PeriodicGarbageCollection.liveJobIdsForCloud(cloud.name)
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

    private static SlurmAgent agent(StatusCloud cloud, String name) throws Exception {
        return new SlurmAgent(
                name,
                "test agent",
                "/tmp",
                1,
                Node.Mode.NORMAL,
                "linux",
                new SlurmLauncher(),
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
