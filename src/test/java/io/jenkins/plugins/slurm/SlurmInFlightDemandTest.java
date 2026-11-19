package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
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

    private static WorkflowRun scheduleWaiting(JenkinsRule j, String name) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("node('testcloud') {\n  echo 'ran'\n}\n", false));
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
        return new SlurmAgent(
                name,
                "test agent",
                "/tmp",
                1,
                Node.Mode.NORMAL,
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
