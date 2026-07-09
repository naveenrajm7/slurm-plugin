package io.jenkins.plugins.slurm;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.RetentionStrategy;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

/** Shared fixtures for Slurm plugin integration tests. */
final class SlurmTestHelper {

    private SlurmTestHelper() {}

    static SlurmCloud createCloud(String name, int maxAgents) {
        return new SlurmCloud(name, "http://localhost:6820", null, "compute", maxAgents, 60);
    }

    static SlurmJobTemplate createTemplate(String name, String label, int instanceCap) {
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setName(name);
        template.setLabel(label);
        template.setPartition("compute");
        template.setInstanceCap(instanceCap);
        return template;
    }

    static SlurmCloud registerCloudWithTemplate(
            jenkins.model.Jenkins jenkins, String cloudName, int maxAgents, SlurmJobTemplate template) {
        SlurmCloud cloud = createCloud(cloudName, maxAgents);
        cloud.setJobTemplates(Collections.singletonList(template));
        jenkins.clouds.add(cloud);
        return cloud;
    }

    static SlurmAgent createAgent(String agentName, String cloudName, String templateId) throws Exception {
        return new SlurmAgent(
                agentName,
                "test agent",
                "/tmp",
                1,
                Node.Mode.NORMAL,
                "linux",
                new SlurmLauncher(),
                RetentionStrategy.INSTANCE,
                Collections.emptyList(),
                cloudName,
                templateId,
                "compute",
                new ProvisioningActivity.Id(cloudName, "template", agentName));
    }

    /**
     * Waits for {@link SlurmCloud#provision} async work to finish before JenkinsRule teardown.
     *
     * <p>Provisioned nodes start agent creation and {@code computer.connect()} on background threads.
     * Without draining those threads, CI can fail with {@code DirectoryNotEmptyException} when the
     * test harness deletes {@code target/tmp} while launch threads are still writing logs.
     */
    static void awaitAsyncProvisioning(jenkins.model.Jenkins jenkins, Collection<PlannedNode> planned)
            throws InterruptedException, TimeoutException {
        for (PlannedNode node : planned) {
            try {
                node.future.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                // Launch is not configured in unit tests; agent creation may still fail asynchronously.
            }
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            boolean busy = false;
            for (Computer computer : jenkins.getComputers()) {
                if (computer instanceof SlurmComputer slurmComputer && slurmComputer.isLaunching()) {
                    busy = true;
                    break;
                }
            }
            if (!busy) {
                Thread.sleep(250);
                return;
            }
            Thread.sleep(100);
        }
        throw new TimeoutException("Timed out waiting for Slurm agent launch threads to finish");
    }

    static Collection<PlannedNode> provisionAndAwait(
            SlurmCloud cloud, jenkins.model.Jenkins jenkins, String label, int excessWorkload)
            throws InterruptedException, TimeoutException {
        Collection<PlannedNode> planned =
                cloud.provision(new hudson.slaves.Cloud.CloudState(hudson.model.Label.get(label), 0), excessWorkload);
        awaitAsyncProvisioning(jenkins, planned);
        return planned;
    }
}
