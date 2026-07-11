package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VmocsConfig}.
 */
public class VmocsConfigTest {

    @Test
    public void testDefaults() {
        VmocsConfig config = new VmocsConfig();

        assertEquals("", config.getVmImage());
        assertEquals(VmocsConfig.DEFAULT_SSH_USER, config.getSshUser());
        assertEquals(VmocsConfig.DEFAULT_SSH_PORT, config.getSshPort());
        assertEquals("", config.getSshKeyPath());
        assertEquals("", config.getAgentJarPath());
        assertEquals(VmocsConfig.DEFAULT_VM_BOOT_TIMEOUT_SEC, config.getVmBootTimeoutSec());
    }

    @Test
    public void testIsConfiguredRequiresVmImage() {
        VmocsConfig config = new VmocsConfig();
        assertFalse(config.isConfigured(), "empty vmImage → not configured");

        config.setVmImage("  ");
        assertFalse(config.isConfigured(), "blank vmImage → not configured");

        config.setVmImage("base-ubuntu");
        assertTrue(config.isConfigured(), "non-empty vmImage → configured");
    }

    @Test
    public void testSettersAndGetters() {
        VmocsConfig config = new VmocsConfig();

        config.setVmImage("windows11-gfx1101");
        assertEquals("windows11-gfx1101", config.getVmImage());

        config.setSshUser("ubuntu");
        assertEquals("ubuntu", config.getSshUser());

        config.setSshPort(60300);
        assertEquals(60300, config.getSshPort());

        config.setSshKeyPath("/opt/vmocs/keys/vagrant_insecure_key");
        assertEquals("/opt/vmocs/keys/vagrant_insecure_key", config.getSshKeyPath());

        config.setAgentJarPath("/opt/jenkins/agent.jar");
        assertEquals("/opt/jenkins/agent.jar", config.getAgentJarPath());

        config.setVmBootTimeoutSec(600);
        assertEquals(600, config.getVmBootTimeoutSec());
    }

    @Test
    public void testNullHandling() {
        VmocsConfig config = new VmocsConfig();

        config.setVmImage(null);
        assertEquals("", config.getVmImage());

        config.setSshUser(null);
        assertEquals(VmocsConfig.DEFAULT_SSH_USER, config.getSshUser());

        config.setSshKeyPath(null);
        assertEquals("", config.getSshKeyPath());

        config.setAgentJarPath(null);
        assertEquals("", config.getAgentJarPath());

        config.setVmBootTimeoutSec(null);
        assertEquals(VmocsConfig.DEFAULT_VM_BOOT_TIMEOUT_SEC, config.getVmBootTimeoutSec());
    }

    @Test
    public void testNonPositivePortAndTimeoutFallback() {
        VmocsConfig config = new VmocsConfig();

        config.setSshPort(0);
        assertEquals(VmocsConfig.DEFAULT_SSH_PORT, config.getSshPort(), "port=0 should fall back to default");

        config.setSshPort(-1);
        assertEquals(VmocsConfig.DEFAULT_SSH_PORT, config.getSshPort(), "port<0 should fall back to default");

        config.setVmBootTimeoutSec(0);
        assertEquals(
                VmocsConfig.DEFAULT_VM_BOOT_TIMEOUT_SEC,
                config.getVmBootTimeoutSec(),
                "timeout=0 should fall back to default");
    }

    @Test
    public void testSshUserFallsBackToDefault() {
        VmocsConfig config = new VmocsConfig();

        config.setSshUser("");
        assertEquals(VmocsConfig.DEFAULT_SSH_USER, config.getSshUser());

        config.setSshUser("   ");
        assertEquals(VmocsConfig.DEFAULT_SSH_USER, config.getSshUser());
    }
}
