package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.slaves.Cloud;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies per-cloud {@code maxAgents} and per-template {@code instanceCap} limits during provisioning.
 * Slurm enforces limits inline in {@link SlurmCloud} rather than via a separate tracker class.
 */
@WithJenkins
class SlurmProvisioningLimitsTest {

    @Test
    void canProvision_falseWhenCloudAtCapacity(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 5);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "cluster-a", 2, template);

        j.jenkins.addNode(SlurmTestHelper.createAgent("agent-1", cloud.name, template.getId()));
        j.jenkins.addNode(SlurmTestHelper.createAgent("agent-2", cloud.name, template.getId()));

        Cloud.CloudState state = new Cloud.CloudState(Label.get("linux"), 0);
        assertFalse(cloud.canProvision(state));
    }

    @Test
    void provision_respectsCloudMaxAgents(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 10);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "cluster-b", 2, template);

        assertEquals(2, SlurmTestHelper.provisionAndAwait(cloud, j.jenkins, "linux", 5).size());
    }

    @Test
    void provision_respectsTemplateInstanceCap(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("gpu", "gpu", 2);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "cluster-c", 10, template);

        assertEquals(2, SlurmTestHelper.provisionAndAwait(cloud, j.jenkins, "gpu", 5).size());
    }

    @Test
    void provision_existingAgentsReduceAvailableCapacity(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 5);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "cluster-d", 5, template);

        j.jenkins.addNode(SlurmTestHelper.createAgent("existing-1", cloud.name, template.getId()));
        j.jenkins.addNode(SlurmTestHelper.createAgent("existing-2", cloud.name, template.getId()));

        assertEquals(3, SlurmTestHelper.provisionAndAwait(cloud, j.jenkins, "linux", 10).size());
    }

    @Test
    void multipleCloudsEnforceIndependentLimits(JenkinsRule j) throws Exception {
        SlurmJobTemplate templateOne = SlurmTestHelper.createTemplate("cpu-1", "linux-one", 5);
        SlurmJobTemplate templateTwo = SlurmTestHelper.createTemplate("cpu-2", "linux-two", 5);
        SlurmCloud cloudOne = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "cloud-one", 2, templateOne);
        SlurmCloud cloudTwo = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "cloud-two", 3, templateTwo);

        assertEquals(2, SlurmTestHelper.provisionAndAwait(cloudOne, j.jenkins, "linux-one", 10).size());
        assertEquals(3, SlurmTestHelper.provisionAndAwait(cloudTwo, j.jenkins, "linux-two", 10).size());
    }
}
