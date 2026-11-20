package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.slaves.Cloud;
import hudson.slaves.RetentionStrategy;
import java.util.Collection;
import java.util.Collections;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * One queued build keeps a single in-flight agent (issue #58).
 *
 * <p>A not-yet-connected agent whose template label is several words has to count for
 * {@code gfx1101}, and a second provision for that label must not plan another node. An agent
 * that cannot claim a queued build is surplus and must not wait out the connect timeout.
 */
@WithJenkins
class SlurmInFlightDemandTest {

    private static final String LABELS = "navi32 rocmtest gfx1101 miopen";

    @Test
    void notYetConnectedMultiWordLabel_blocksAnotherProvision(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("gfx1101", LABELS, 50);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "mici-alola", 50, template);

        SlurmAgent waiting = agent(cloud.name, "alola-gfx1101-waiting", template.getId(), LABELS);
        j.jenkins.addNode(waiting);
        SlurmComputer computer = (SlurmComputer) waiting.toComputer();
        assertNotNull(computer);
        computer.setAcceptingTasks(false);

        Label gfx1101 = j.jenkins.getLabel("gfx1101");
        assertNotNull(gfx1101);
        assertTrue(gfx1101.getNodes().contains(waiting));
        assertTrue(
                InProvisioning.getAllInProvisioning(gfx1101).contains(waiting.getNodeName()),
                "a launching agent with a multi-word template label has to count as in provisioning");

        Collection<?> planned = cloud.provision(new Cloud.CloudState(gfx1101, 0), 1);
        assertEquals(0, planned.size(), "one in-flight agent covers the one queued executor");
    }

    @Test
    void unclaimedAgent_claimsTheWaitingBuild(JenkinsRule j) throws Exception {
        JobUtils.clearProvisioningClaimsForTest();
        j.jenkins.setNumExecutors(2);
        WorkflowRun run = null;
        try {
            run = scheduleWaiting(j, "one-build");
            SlurmAgent agent = agent("mici-alola", "late-agent", "template", "testcloud");
            assertTrue(agent.getLabelString().contains("testcloud"));
            assertFalse(
                    SlurmLauncher.shouldStopWaitingForBuild(agent),
                    "the waiting build must keep this agent");
            assertNotNull(agent.getProvisioningQueueItemId());
        } finally {
            JobUtils.clearProvisioningClaimsForTest();
            abortIfBuilding(j, run);
        }
    }

    @Test
    void secondAgent_stopsWhenTheOnlyBuildIsAlreadyClaimed(JenkinsRule j) throws Exception {
        JobUtils.clearProvisioningClaimsForTest();
        j.jenkins.setNumExecutors(2);
        WorkflowRun run = null;
        try {
            run = scheduleWaiting(j, "claimed-build");
            Label label = j.jenkins.getLabel("testcloud");
            JobUtils.ProvisioningOwner owner = JobUtils.claimProvisioningOwner(label, "testcloud", "agent-a");
            assertNotNull(owner);

            SlurmAgent claimed = agent("mici-alola", "agent-a", "template", "testcloud");
            claimed.trackProvisioningOwner(owner);
            SlurmAgent surplus = agent("mici-alola", "agent-b", "template", "testcloud");

            assertFalse(SlurmLauncher.shouldStopWaitingForBuild(claimed));
            assertTrue(
                    SlurmLauncher.shouldStopWaitingForBuild(surplus),
                    "the extra agent has no build of its own and must be cancelled");
            assertNotNull(claimed.getProvisioningQueueItemId());
        } finally {
            JobUtils.clearProvisioningClaimsForTest();
            abortIfBuilding(j, run);
        }
    }

    @Test
    void unlabeledQueuedBuild_mustKeepNormalAgent(JenkinsRule j) throws Exception {
        JobUtils.clearProvisioningClaimsForTest();
        j.jenkins.setNumExecutors(0);
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("any", "", 4);
        template.setNodeUsageMode(Node.Mode.NORMAL);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "testcloud", 4, template);
        WorkflowRun run = null;
        try {
            run = schedule(j, "unlabeled", "node {\n  echo 'ran'\n}\n");
            Queue.Item[] queued = j.jenkins.getQueue().getItems();
            assertEquals(1, queued.length);
            assertNull(queued[0].getAssignedLabel());
            assertTrue(
                    cloud.canProvision(new Cloud.CloudState(null, 0)),
                    "a NORMAL template with an empty label can provision unlabeled work");

            SlurmAgent exclusive = agent("testcloud", "exclusive-agent", template.getId(), "", Node.Mode.EXCLUSIVE);
            assertTrue(
                    SlurmLauncher.shouldStopWaitingForBuild(exclusive),
                    "an EXCLUSIVE agent must not take an unlabeled build");
            assertNull(exclusive.getProvisioningQueueItemId());

            SlurmAgent normal = agent("testcloud", "normal-agent", template.getId(), "", Node.Mode.NORMAL);
            assertFalse(
                    SlurmLauncher.shouldStopWaitingForBuild(normal),
                    "a NORMAL agent must keep the unlabeled queued build");
            assertNotNull(normal.getProvisioningQueueItemId());
        } finally {
            JobUtils.clearProvisioningClaimsForTest();
            abortIfBuilding(j, run);
        }
    }

    @Test
    void excludedAgent_mustNotStealEligibleAgentsClaim(JenkinsRule j) throws Exception {
        JobUtils.clearProvisioningClaimsForTest();
        j.jenkins.setNumExecutors(0);
        WorkflowRun run = null;
        try {
            run = schedule(j, "exclude-name", "node('testcloud && !excluded-agent') {\n  echo 'ran'\n}\n");
            SlurmAgent excluded = agent("mici-alola", "excluded-agent", "template", "testcloud");
            SlurmAgent eligible = agent("mici-alola", "eligible-agent", "template", "testcloud");
            Label required = Label.parseExpression("testcloud && !excluded-agent");
            assertFalse(required.matches(excluded), "Jenkins rejects the agent named in the exclusion");
            assertTrue(required.matches(eligible), "Jenkins accepts the other agent");

            boolean excludedStops = SlurmLauncher.shouldStopWaitingForBuild(excluded);
            boolean eligibleStops = SlurmLauncher.shouldStopWaitingForBuild(eligible);
            assertTrue(excludedStops, "the excluded agent must not keep the queue item");
            assertFalse(eligibleStops, "the eligible agent must claim the waiting build");
            assertNull(excluded.getProvisioningQueueItemId());
            assertNotNull(eligible.getProvisioningQueueItemId());
        } finally {
            JobUtils.clearProvisioningClaimsForTest();
            abortIfBuilding(j, run);
        }
    }

    private static WorkflowRun scheduleWaiting(JenkinsRule j, String name) throws Exception {
        return schedule(j, name, "node('testcloud') {\n  echo 'ran'\n}\n");
    }

    private static WorkflowRun schedule(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, false));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", run);
        return run;
    }

    private static void abortIfBuilding(JenkinsRule j, WorkflowRun run) throws Exception {
        if (run != null && run.isBuilding() && run.getExecution() != null) {
            run.getExecution().interrupt(Result.ABORTED);
            j.waitForCompletion(run);
        }
    }

    private static SlurmAgent agent(String cloudName, String name, String templateId, String labels)
            throws Exception {
        return agent(cloudName, name, templateId, labels, Node.Mode.NORMAL);
    }

    private static SlurmAgent agent(
            String cloudName, String name, String templateId, String labels, Node.Mode mode) throws Exception {
        return new SlurmAgent(
                name,
                "test agent",
                "/tmp",
                1,
                mode,
                labels,
                new NoLaunchLauncher(),
                noConnect(),
                Collections.emptyList(),
                cloudName,
                templateId,
                "compute",
                new ProvisioningActivity.Id(cloudName, "template", name));
    }

    private static RetentionStrategy<Computer> noConnect() {
        return new RetentionStrategy<>() {
            @Override
            public long check(Computer c) {
                return 60;
            }

            @Override
            public void start(Computer c) {
                // The test owns accepting-tasks. Do not connect.
            }
        };
    }
}
