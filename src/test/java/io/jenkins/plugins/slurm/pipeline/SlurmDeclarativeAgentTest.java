package io.jenkins.plugins.slurm.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SlurmDeclarativeAgent}.
 */
public class SlurmDeclarativeAgentTest {

    @Test
    public void testDefaults() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();
        assertNull(agent.getCloud());
        assertNull(agent.getLabel());
        assertNull(agent.getJson());
        assertNull(agent.getJsonFile());
    }

    @Test
    public void testSettersAndGetters() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();

        agent.setCloud("test-cloud");
        assertEquals("test-cloud", agent.getCloud());

        agent.setLabel("gpu");
        assertTrue(
                agent.getLabel().startsWith("gpu-"),
                "getLabel() should return the user label with a unique suffix, got: " + agent.getLabel());

        agent.setPartition("gpu");
        assertEquals("gpu", agent.getPartition());

        agent.setCpus(16);
        assertEquals(Integer.valueOf(16), agent.getCpus());
    }

    @Test
    public void testJsonConfiguration() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();
        String json = "{\"partition\": \"gpu\", \"cpus\": 16}";

        agent.setJson(json);
        assertEquals(json, agent.getJson());
    }

    @Test
    public void testJsonFileConfiguration() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();

        agent.setJsonFile(".jenkins/slurm-config.json");
        assertEquals(".jenkins/slurm-config.json", agent.getJsonFile());
    }

    @Test
    public void testHasScmContextWithoutScript() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();

        // Without script context, should return false
        assertFalse(agent.hasScmContext(null));
    }

    @Test
    public void testContainerConfiguration() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();

        agent.setContainerImage("/path/to/container.sqsh");
        assertEquals("/path/to/container.sqsh", agent.getContainerImage());

        agent.setContainerMounts("/data:/data");
        assertEquals("/data:/data", agent.getContainerMounts());

        agent.setContainerWorkdir("/workspace");
        assertEquals("/workspace", agent.getContainerWorkdir());

        agent.setContainerMountHome(true);
        assertEquals(Boolean.TRUE, agent.getContainerMountHome());
    }

    @Test
    public void testAdvancedConfiguration() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();

        agent.setAccount("my-account");
        assertEquals("my-account", agent.getAccount());

        agent.setQos("high");
        assertEquals("high", agent.getQos());

        agent.setReservation("my-reservation");
        assertEquals("my-reservation", agent.getReservation());

        agent.setConstraints("gpu&nvme");
        assertEquals("gpu&nvme", agent.getConstraints());
    }

    @Test
    public void testNativeAgentProperties() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();
        agent.setCloud("test-cloud");
        agent.setJavaPath("/opt/jenkins/jdk-17/bin/java");
        agent.setJarPath("/opt/jenkins/agent.jar");
        agent.setSetupScript("module load java/21");

        Map<String, Object> args = agent.getAsArgs();
        assertEquals("/opt/jenkins/jdk-17/bin/java", args.get("javaPath"));
        assertEquals("/opt/jenkins/agent.jar", args.get("jarPath"));
        assertEquals("module load java/21", args.get("setupScript"));
    }

    @Test
    public void testResourceConfiguration() {
        SlurmDeclarativeAgent agent = new SlurmDeclarativeAgent();

        agent.setMemory("32G");
        assertEquals("32G", agent.getMemory());

        agent.setTime("02:00:00");
        assertEquals("02:00:00", agent.getTime());

        agent.setGres("gpu:gfx1030:1");
        assertEquals("gpu:gfx1030:1", agent.getGres());

        agent.setNodes("2");
        assertEquals("2", agent.getNodes());

        agent.setTasks(4);
        assertEquals(Integer.valueOf(4), agent.getTasks());
    }

    // Note: testDescriptor removed because it requires pipeline-model-definition runtime dependencies
    // that aren't available in unit test context (WithScriptAllowlist)
}
