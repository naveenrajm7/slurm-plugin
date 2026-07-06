package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentLaunchConfig}.
 */
public class AgentLaunchConfigTest {

    @Test
    public void testDefaults() {
        AgentLaunchConfig config = new AgentLaunchConfig();
        assertEquals("java", config.getJavaPath());
        assertEquals("", config.getJarPath());
        assertFalse(config.getDownloadJar());
        assertEquals("", config.getSetupScript());
        assertFalse(config.isConfigured());
    }

    @Test
    public void testJarPathConfigured() {
        AgentLaunchConfig config = new AgentLaunchConfig();
        config.setJarPath("/opt/jenkins/agent.jar");
        config.setJavaPath("/usr/bin/java");

        assertTrue(config.isConfigured());
        assertEquals("/usr/bin/java", config.getJavaPath());
        config.validateNativeLaunch();
    }

    @Test
    public void testDownloadJarConfigured() {
        AgentLaunchConfig config = new AgentLaunchConfig();
        config.setDownloadJar(true);

        assertTrue(config.isConfigured());
        config.validateNativeLaunch();
    }

    @Test
    public void testValidateFailsWhenUnconfigured() {
        AgentLaunchConfig config = new AgentLaunchConfig();
        assertThrows(IllegalStateException.class, config::validateNativeLaunch);
    }

    @Test
    public void testMergeCloudDefaultsWithTemplateOverride() {
        AgentLaunchConfig cloud = new AgentLaunchConfig();
        cloud.setJavaPath("/opt/jenkins/jdk-17/bin/java");
        cloud.setJarPath("/opt/jenkins/agent.jar");

        AgentLaunchConfig template = new AgentLaunchConfig();
        template.setJavaPath("/usr/bin/java");

        AgentLaunchConfig merged = AgentLaunchConfig.merge(cloud, template);
        assertEquals("/usr/bin/java", merged.getJavaPath());
        assertEquals("/opt/jenkins/agent.jar", merged.getJarPath());
    }

    @Test
    public void testMergeTemplateInheritsCloudWhenUnset() {
        AgentLaunchConfig cloud = new AgentLaunchConfig();
        cloud.setJavaPath("/opt/jenkins/jdk-17/bin/java");
        cloud.setJarPath("/opt/jenkins/agent.jar");

        AgentLaunchConfig merged = AgentLaunchConfig.merge(cloud, new AgentLaunchConfig());
        assertEquals("/opt/jenkins/jdk-17/bin/java", merged.getJavaPath());
        assertEquals("/opt/jenkins/agent.jar", merged.getJarPath());
    }
}
