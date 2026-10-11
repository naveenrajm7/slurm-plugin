package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecution;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression coverage for a single {@code provision()} call planning more than one agent.
 *
 * <p>A millisecond timestamp is not unique inside that loop. Replacing the existing Jenkins node
 * then leaves {@code launch()} holding a different {@link SlurmAgent} from the one Jenkins tracks,
 * and garbage collection cancels the live Slurm job.
 */
class SlurmAgentNameCollisionTest {

    @Test
    void nextAgentName_differsWithinTheSameMillisecond() {
        AtomicLong sequence = new AtomicLong();
        String first = SlurmCloud.nextAgentName("cloud-cpu-", () -> 1_700_000_000_000L, sequence::incrementAndGet, name -> false);
        String second = SlurmCloud.nextAgentName("cloud-cpu-", () -> 1_700_000_000_000L, sequence::incrementAndGet, name -> false);

        assertNotEquals(first, second);
        assertTrue(first.startsWith("cloud-cpu-1700000000000-"));
        assertTrue(second.startsWith("cloud-cpu-1700000000000-"));
    }

    @Test
    void nextAgentName_skipsNamesAlreadyTaken() {
        AtomicLong sequence = new AtomicLong();
        String taken = SlurmCloud.nextAgentName("cloud-cpu-", () -> 5L, sequence::incrementAndGet, name -> false);
        sequence.set(0);

        String chosen = SlurmCloud.nextAgentName("cloud-cpu-", () -> 5L, sequence::incrementAndGet, taken::equals);

        assertNotEquals(taken, chosen);
        assertEquals("cloud-cpu-5-2", chosen);
    }

    @Test
    void nextAgentName_failsWhenEveryCandidateIsTaken() {
        AtomicLong sequence = new AtomicLong();
        assertThrows(
                IllegalStateException.class,
                () -> SlurmCloud.nextAgentName("cloud-cpu-", () -> 1L, sequence::incrementAndGet, name -> true));
    }

    @Nested
    @WithJenkins
    class ProvisionTwoAgents {

        @Test
        void provision_workloadTwo_keepsDistinctRegisteredAgents(JenkinsRule j) throws Exception {
            SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 4);
            template.setRunOnce(false);
            template.setIdleMinutes(60);

            HoldingCloud cloud = new HoldingCloud();
            cloud.setJobTemplates(List.of(template));
            j.jenkins.clouds.add(cloud);
            SlurmJobHeartbeats.clearForTest();

            try {
                Label label = Label.get("linux");
                Collection<PlannedNode> planned =
                        cloud.provision(new Cloud.CloudState(label, 0), 2);
                assertEquals(2, planned.size());

                Set<String> plannedNames =
                        planned.stream().map(node -> node.displayName).collect(Collectors.toSet());
                assertEquals(2, plannedNames.size(), "one provision() call must plan two different node names");

                for (CloudProvisioningListener listener : CloudProvisioningListener.all()) {
                    listener.onStarted(cloud, label, planned);
                }
                cloud.gate.countDown();

                List<SlurmAgent> created = new ArrayList<>();
                for (PlannedNode node : planned) {
                    Node result = node.future.get(30, TimeUnit.SECONDS);
                    assertInstanceOf(SlurmAgent.class, result);
                    created.add((SlurmAgent) result);
                }

                for (SlurmAgent agent : created) {
                    awaitJobId(agent);
                }

                Set<String> jobIds = created.stream().map(SlurmAgent::getSlurmJobId).collect(Collectors.toSet());
                assertEquals(2, jobIds.size(), "each launch submits its own Slurm job id");

                for (SlurmAgent agent : created) {
                    SlurmAgent registered = assertInstanceOf(
                            SlurmAgent.class, j.jenkins.getNode(agent.getNodeName()));
                    SlurmAgent launched = cloud.launchedByLaunch.get(agent.getNodeName());
                    assertNotNull(launched, "launch() did not record " + agent.getNodeName());
                    assertSame(launched, registered, "launch() updated a different agent than Jenkins tracks");
                    assertSame(agent, registered);
                    assertEquals(launched.getSlurmJobId(), registered.getSlurmJobId());

                    assertNotNull(registered.toComputer());

                    ProvisioningActivity activity = CloudStatistics.get().getActivityFor(registered);
                    assertNotNull(activity, "missing cloud-stats activity for " + registered.getNodeName());
                    assertEquals(registered.getNodeName(), activity.getId().getNodeName());
                    assertNotNull(activity.getPhaseExecution(ProvisioningActivity.Phase.OPERATING));

                    PhaseExecution launching = activity.getPhaseExecution(ProvisioningActivity.Phase.LAUNCHING);
                    assertNotNull(launching);
                    String submitted = "Submitted Slurm job " + registered.getSlurmJobId();
                    assertTrue(
                            launching.getAttachments().stream()
                                    .map(PhaseExecutionAttachment::getTitle)
                                    .anyMatch(submitted::equals),
                            "launch attachment missing from the activity that reached OPERATING");
                }

                List<ProvisioningActivity> activities = CloudStatistics.get().getActivities().stream()
                        .filter(activity -> cloud.name.equals(activity.getId().getCloudName()))
                        .toList();
                assertEquals(2, activities.size(), "cloud-stats should have one activity per planned name");
                assertEquals(
                        plannedNames,
                        activities.stream()
                                .map(activity -> activity.getId().getNodeName())
                                .collect(Collectors.toSet()));

                Set<String> liveJobIds =
                        SlurmGarbageCollection.PeriodicGarbageCollection.liveJobIdsForCloud(cloud.name);
                assertEquals(jobIds, liveJobIds);

                SlurmGarbageCollection.PeriodicGarbageCollection.refreshLiveAgents();
                long afterTimeout = System.currentTimeMillis() + Duration.ofHours(1).toMillis();
                for (String jobId : jobIds) {
                    assertNotNull(SlurmJobHeartbeats.getLastRefresh(jobId));
                    assertFalse(
                            SlurmGarbageCollection.shouldCancelOrphan(
                                    jobId, liveJobIds, Duration.ofMinutes(5), afterTimeout),
                            "registered agent job " + jobId + " must not be treated as an orphan");
                    assertTrue(
                            SlurmGarbageCollection.shouldCancelOrphan(
                                    jobId, Set.of(), Duration.ofMinutes(5), afterTimeout),
                            "the same job is an orphan once no registered agent owns it");
                }
            } finally {
                cloud.gate.countDown();
                for (Node node : new ArrayList<>(j.jenkins.getNodes())) {
                    if (node instanceof SlurmAgent) {
                        j.jenkins.removeNode(node);
                    }
                }
                SlurmJobHeartbeats.clearForTest();
                SlurmTestHelper.awaitLaunchThreadsSettled(j.jenkins);
            }
        }

        @Test
        void addAgentIfAbsent_refusesToReplaceAnExistingNode(JenkinsRule j) throws Exception {
            SlurmCloud cloud = SlurmTestHelper.createCloud("collision-cloud", 10);
            SlurmAgent existing = SlurmTestHelper.createStaticAgent("same-name", cloud.name, "template-id");
            existing.setSlurmJobId("4242");
            j.jenkins.addNode(existing);

            SlurmAgent replacement = SlurmTestHelper.createStaticAgent("same-name", cloud.name, "template-id");
            IOException failure = assertThrows(IOException.class, () -> cloud.addAgentIfAbsent(replacement));

            assertTrue(failure.getMessage().contains("same-name"));
            assertSame(existing, j.jenkins.getNode("same-name"));
            assertEquals("4242", ((SlurmAgent) j.jenkins.getNode("same-name")).getSlurmJobId());
        }
    }

    private static void awaitJobId(SlurmAgent agent) throws InterruptedException, TimeoutException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            if (agent.getSlurmJobId() != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new TimeoutException("launch() did not store a job id on " + agent.getNodeName());
    }

    /** Cloud whose launcher records the agent instance {@code launch()} updated. */
    public static final class HoldingCloud extends SlurmCloud {
        final transient CountDownLatch gate = new CountDownLatch(1);
        final transient ConcurrentMap<String, SlurmAgent> launchedByLaunch = new ConcurrentHashMap<>();
        final transient AtomicInteger nextJobId = new AtomicInteger(1000);

        public HoldingCloud() {
            super("collision-cloud", "http://127.0.0.1:9", null, "compute", 10, 1);
        }

        @Override
        protected void beforeAgentRegistration(String agentName) {
            try {
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to register " + agentName);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to register " + agentName, e);
            }
        }

        @Override
        protected ComputerLauncher createLauncher() {
            return new RecordingLauncher(this);
        }
    }

    /**
     * Stands in for {@link SlurmLauncher#launch}: writes the job id onto the computer's node and
     * attaches the launching cloud-stats record. Entering {@code OPERATING} is what cloud-stats does
     * from {@code ComputerListener.onOnline} once that same computer connects.
     */
    public static final class RecordingLauncher extends ComputerLauncher {
        private transient HoldingCloud cloud;

        public RecordingLauncher() {}

        RecordingLauncher(HoldingCloud cloud) {
            this.cloud = cloud;
        }

        @Override
        public void launch(SlaveComputer computer, TaskListener listener) {
            if (!(computer instanceof SlurmComputer slurmComputer)) {
                throw new IllegalStateException("Expected a Slurm computer");
            }
            SlurmAgent agent = slurmComputer.getNode();
            if (agent == null) {
                throw new IllegalStateException("Computer " + computer.getName() + " has no agent");
            }
            if (cloud == null) {
                throw new IllegalStateException("Recording launcher was restored without its test cloud");
            }
            if (agent.getSlurmJobId() != null) {
                return;
            }

            String jobId = Integer.toString(cloud.nextJobId.incrementAndGet());
            cloud.launchedByLaunch.put(agent.getNodeName(), agent);
            agent.setSlurmJobId(jobId);
            SlurmJobHeartbeats.refresh(agent.getCloudName(), jobId, agent.getNodeName());
            SlurmCloudStats.attachLaunchingOk(agent, "Submitted Slurm job " + jobId);
            for (ComputerListener computerListener : ComputerListener.all()) {
                if (computerListener instanceof CloudStatistics.OperationListener operationListener) {
                    operationListener.onOnline(computer, listener);
                }
            }
        }
    }
}
