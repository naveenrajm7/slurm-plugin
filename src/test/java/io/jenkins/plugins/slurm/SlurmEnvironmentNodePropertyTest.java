package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.EnvVars;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlurmEnvironmentNodePropertyTest {

    @Test
    void buildEnvVars_contributesJobIdAndNodeList(JenkinsRule j) throws Exception {
        SlurmEnvironmentNodeProperty prop = new SlurmEnvironmentNodeProperty("42", "node01");

        EnvVars env = new EnvVars();
        prop.buildEnvVars(env, hudson.model.TaskListener.NULL);

        assertEquals("42", env.get("SLURM_JOB_ID"));
        assertEquals("node01", env.get("SLURM_NODELIST"));
    }

    @Test
    void buildEnvVars_skipsNullValues(JenkinsRule j) throws Exception {
        SlurmEnvironmentNodeProperty prop = new SlurmEnvironmentNodeProperty(null, null);

        EnvVars env = new EnvVars();
        prop.buildEnvVars(env, hudson.model.TaskListener.NULL);

        assertNull(env.get("SLURM_JOB_ID"));
        assertNull(env.get("SLURM_NODELIST"));
    }

    @Test
    void buildEnvVars_skipsBlankValues(JenkinsRule j) throws Exception {
        SlurmEnvironmentNodeProperty prop = new SlurmEnvironmentNodeProperty("  ", "");

        EnvVars env = new EnvVars();
        prop.buildEnvVars(env, hudson.model.TaskListener.NULL);

        assertNull(env.get("SLURM_JOB_ID"));
        assertNull(env.get("SLURM_NODELIST"));
    }

    @Test
    void setSlurmJobId_updatesNodeProperty(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "env-cloud", 5, template);
        SlurmAgent agent = SlurmTestHelper.createAgent("env-agent", cloud.name, template.getId());
        j.jenkins.addNode(agent);

        // Initially no env prop
        assertNull(findSlurmEnvProp(agent));

        agent.setSlurmJobId("1234");

        SlurmEnvironmentNodeProperty prop = findSlurmEnvProp(agent);
        assertNotNull(prop);
        assertEquals("1234", prop.getSlurmJobId());
        assertNull(prop.getSlurmNodeList());
    }

    @Test
    void setNodeList_updatesNodeProperty(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("gpu", "gpu", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "env-cloud2", 5, template);
        SlurmAgent agent = SlurmTestHelper.createAgent("env-agent2", cloud.name, template.getId());
        j.jenkins.addNode(agent);

        agent.setSlurmJobId("5678");
        agent.setNodeList("gpu01");

        SlurmEnvironmentNodeProperty prop = findSlurmEnvProp(agent);
        assertNotNull(prop);
        assertEquals("5678", prop.getSlurmJobId());
        assertEquals("gpu01", prop.getSlurmNodeList());
    }

    @Test
    void updateSlurmEnvProp_idempotent_singlePropertyAdded(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu2", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "env-cloud3", 5, template);
        SlurmAgent agent = SlurmTestHelper.createAgent("env-agent3", cloud.name, template.getId());
        j.jenkins.addNode(agent);

        // Multiple updates should not create duplicate properties.
        agent.setSlurmJobId("9999");
        agent.setNodeList("c01");
        agent.setNodeList("c01,c02");

        long slurmPropCount = agent.getNodeProperties().stream()
                .filter(p -> p instanceof SlurmEnvironmentNodeProperty)
                .count();
        assertEquals(1, slurmPropCount, "Only one SlurmEnvironmentNodeProperty should exist");
    }

    @Test
    void descriptor_notApplicableToAnyNode(JenkinsRule j) {
        SlurmEnvironmentNodeProperty.DescriptorImpl descriptor =
                new SlurmEnvironmentNodeProperty.DescriptorImpl();

        // Verify it does not appear in any node's property selector UI.
        org.junit.jupiter.api.Assertions.assertFalse(
                descriptor.isApplicable(Node.class),
                "Property should not be user-selectable for generic nodes");
        org.junit.jupiter.api.Assertions.assertFalse(
                descriptor.isApplicable(SlurmAgent.class),
                "Property should not be user-selectable for Slurm agents either");
    }

    // -------------------------------------------------------------------------

    private static SlurmEnvironmentNodeProperty findSlurmEnvProp(SlurmAgent agent) {
        for (NodeProperty<?> prop : agent.getNodeProperties()) {
            if (prop instanceof SlurmEnvironmentNodeProperty) {
                return (SlurmEnvironmentNodeProperty) prop;
            }
        }
        return null;
    }
}
