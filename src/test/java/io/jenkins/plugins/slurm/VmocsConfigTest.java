package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VmocsConfig}.
 */
public class VmocsConfigTest {

    @Test
    public void testDefaults() {
        VmocsConfig config = new VmocsConfig();

        assertEquals("", config.getTemplateName());
        assertEquals(VmocsConfig.DEFAULT_VMOCS_BIN, config.getVmocsBin());
        assertEquals("", config.getConfigPath());
        assertNull(config.getCores());
        assertNull(config.getMemoryMb());
        assertEquals("", config.getPciDevices());
        assertEquals(VmocsConfig.DEFAULT_VM_AGENT_JAR_PATH, config.getAgentJarPath());
        assertEquals(VmocsConfig.DEFAULT_VM_BOOT_TIMEOUT_SEC, config.getVmBootTimeoutSec());
    }

    @Test
    public void testIsConfiguredRequiresTemplateName() {
        VmocsConfig config = new VmocsConfig();
        assertFalse(config.isConfigured(), "empty templateName → not configured");

        config.setTemplateName("  ");
        assertFalse(config.isConfigured(), "blank templateName → not configured");

        config.setTemplateName("base-ubuntu");
        assertTrue(config.isConfigured(), "non-empty templateName → configured");
    }

    @Test
    public void testSettersAndGetters() {
        VmocsConfig config = new VmocsConfig();

        config.setTemplateName("base-ubuntu");
        assertEquals("base-ubuntu", config.getTemplateName());

        config.setVmocsBin("/usr/local/bin/vmocs");
        assertEquals("/usr/local/bin/vmocs", config.getVmocsBin());

        config.setConfigPath("/etc/vmocs/vmocs.yaml");
        assertEquals("/etc/vmocs/vmocs.yaml", config.getConfigPath());

        config.setCores(8);
        assertEquals(8, config.getCores());

        config.setMemoryMb(16384);
        assertEquals(16384, config.getMemoryMb());

        config.setPciDevices("0000:03:00.0,0000:03:00.1");
        assertEquals("0000:03:00.0,0000:03:00.1", config.getPciDevices());

        config.setAgentJarPath("/opt/jenkins/agent.jar");
        assertEquals("/opt/jenkins/agent.jar", config.getAgentJarPath());

        config.setVmBootTimeoutSec(600);
        assertEquals(600, config.getVmBootTimeoutSec());
    }

    @Test
    public void testNullHandling() {
        VmocsConfig config = new VmocsConfig();

        config.setTemplateName(null);
        assertEquals("", config.getTemplateName());

        config.setVmocsBin(null);
        assertEquals(VmocsConfig.DEFAULT_VMOCS_BIN, config.getVmocsBin());

        config.setConfigPath(null);
        assertEquals("", config.getConfigPath());

        config.setCores(null);
        assertNull(config.getCores());

        config.setMemoryMb(null);
        assertNull(config.getMemoryMb());

        config.setPciDevices(null);
        assertEquals("", config.getPciDevices());

        config.setAgentJarPath(null);
        assertEquals("", config.getAgentJarPath());

        config.setVmBootTimeoutSec(null);
        assertEquals(VmocsConfig.DEFAULT_VM_BOOT_TIMEOUT_SEC, config.getVmBootTimeoutSec());
    }

    @Test
    public void testNonPositiveValuesNullified() {
        VmocsConfig config = new VmocsConfig();

        config.setCores(0);
        assertNull(config.getCores(), "cores=0 should be treated as not set");

        config.setCores(-1);
        assertNull(config.getCores(), "cores<0 should be treated as not set");

        config.setMemoryMb(0);
        assertNull(config.getMemoryMb(), "memoryMb=0 should be treated as not set");

        config.setVmBootTimeoutSec(0);
        assertEquals(
                VmocsConfig.DEFAULT_VM_BOOT_TIMEOUT_SEC,
                config.getVmBootTimeoutSec(),
                "vmBootTimeoutSec=0 should fall back to default");
    }

    @Test
    public void testVmocsBinFallsBackToDefault() {
        VmocsConfig config = new VmocsConfig();

        config.setVmocsBin("");
        assertEquals(VmocsConfig.DEFAULT_VMOCS_BIN, config.getVmocsBin());

        config.setVmocsBin("   ");
        assertEquals(VmocsConfig.DEFAULT_VMOCS_BIN, config.getVmocsBin());
    }
}
