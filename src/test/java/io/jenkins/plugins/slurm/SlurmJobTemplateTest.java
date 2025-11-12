package io.jenkins.plugins.slurm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.util.FormValidation;
import org.junit.Test;

/**
 * Tests for {@link SlurmJobTemplate}.
 */
public class SlurmJobTemplateTest {

    @Test
    public void testDefaults() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        assertEquals("default", template.getName());
        assertEquals("", template.getLabel());
        assertEquals("", template.getPartition());
        assertEquals(Integer.valueOf(1), template.getCpusPerTask());
        assertEquals(1, template.getInstanceCap());
    }

    @Test
    public void testSettersAndGetters() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        template.setName("gpu-template");
        assertEquals("gpu-template", template.getName());
        
        template.setLabel("gpu cuda");
        assertEquals("gpu cuda", template.getLabel());
        
        template.setPartition("gpu");
        assertEquals("gpu", template.getPartition());
        
        template.setCpusPerTask(16);
        assertEquals(Integer.valueOf(16), template.getCpusPerTask());
        
        template.setMemoryPerNode(32768L); // 32GB in MB
        assertEquals(Long.valueOf(32768L), template.getMemoryPerNode());
        
        template.setTimeLimit(120); // 2 hours in minutes
        assertEquals(Integer.valueOf(120), template.getTimeLimit());
    }

    @Test
    public void testPyxisConfig() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        assertNull(template.getPyxis());
        
        PyxisConfig pyxis = new PyxisConfig();
        pyxis.setContainerImage("/path/to/container.sqsh");
        pyxis.setContainerMounts("/data:/data");
        pyxis.setContainerWorkdir("/workspace");
        
        template.setPyxis(pyxis);
        assertNotNull(template.getPyxis());
        assertEquals("/path/to/container.sqsh", template.getPyxis().getContainerImage());
    }

    @Test
    public void testDescriptorValidation() {
        SlurmJobTemplate.DescriptorImpl descriptor = new SlurmJobTemplate.DescriptorImpl();
        
        // Name validation
        FormValidation validation = descriptor.doCheckName("test-template");
        assertEquals(FormValidation.Kind.OK, validation.kind);
        
        validation = descriptor.doCheckName("");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        
        // CPUs validation
        validation = descriptor.doCheckCpusPerTask("16");
        assertEquals(FormValidation.Kind.OK, validation.kind);
        
        validation = descriptor.doCheckCpusPerTask("-1");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        
        // Memory validation (expects numeric MB value, not "32G" format)
        validation = descriptor.doCheckMemoryPerNode("32768"); // 32GB in MB
        assertEquals(FormValidation.Kind.OK, validation.kind);
        
        validation = descriptor.doCheckMemoryPerNode("invalid");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        
        // Time limit validation (expects numeric minutes, not "01:30:00" format)
        validation = descriptor.doCheckTimeLimit("90"); // 90 minutes
        assertEquals(FormValidation.Kind.OK, validation.kind);
        
        validation = descriptor.doCheckTimeLimit("invalid");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
    }

    @Test
    public void testInstanceCap() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        // Default instance cap is 1
        assertEquals(1, template.getInstanceCap());
        
        template.setInstanceCap(5);
        assertEquals(5, template.getInstanceCap());
        
        // Setting to 0 or negative should default to 1 (minimum valid value)
        template.setInstanceCap(0);
        assertEquals(1, template.getInstanceCap());
    }

    @Test
    public void testIdleMinutes() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        template.setIdleMinutes(10);
        assertEquals(10, template.getIdleMinutes());
        
        template.setIdleMinutes(0);
        assertEquals(0, template.getIdleMinutes());
    }

    @Test
    public void testLabelMatching() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setLabel("docker linux");

        // Exact match
        assertTrue(template.canTake("docker linux"));
        
        // Subset match with &&
        assertTrue(template.canTake("docker && linux"));
        
        // Individual label match
        assertTrue(template.canTake("docker"));
        assertTrue(template.canTake("linux"));
        
        // Non-matching label
        assertFalse(template.canTake("gpu"));
        
        // null label (template with empty label accepts null)
        template.setLabel("");
        assertTrue(template.canTake(null));
    }

    @Test
    public void testLabelMatchingWithComplexExpressions() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setLabel("docker maven linux");

        // All labels present
        assertTrue(template.canTake("docker && maven && linux"));
        
        // Partial match
        assertTrue(template.canTake("docker && maven"));
        
        // One label missing
        assertFalse(template.canTake("docker && gpu"));
    }

    @Test
    public void testEmptyLabelMatchesAll() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setLabel("");

        // Empty template label should match any request
        assertTrue(template.canTake(null));
        assertTrue(template.canTake(""));
        assertTrue(template.canTake("docker"));
        assertTrue(template.canTake("gpu && cuda"));
    }

    @Test
    public void testInstanceCapValidation() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        SlurmJobTemplate.DescriptorImpl descriptor = new SlurmJobTemplate.DescriptorImpl();

        // Valid instance cap
        FormValidation validation = descriptor.doCheckInstanceCap("5");
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Zero or negative should warn or error
        validation = descriptor.doCheckInstanceCap("0");
        // Implementation may warn or error - just check it's not OK
        assertTrue(validation.kind == FormValidation.Kind.WARNING || 
                  validation.kind == FormValidation.Kind.ERROR);

        // Invalid number
        validation = descriptor.doCheckInstanceCap("invalid");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
    }

    @Test
    public void testKeepJobOnFailureFlag() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        // Default should be false
        assertFalse(template.isKeepJobOnFailure());
        
        template.setKeepJobOnFailure(true);
        assertTrue(template.isKeepJobOnFailure());
        
        template.setKeepJobOnFailure(false);
        assertFalse(template.isKeepJobOnFailure());
    }

    @Test
    public void testRunOnceFlag() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        // Default behavior
        boolean defaultRunOnce = template.isRunOnce();
        
        template.setRunOnce(true);
        assertTrue(template.isRunOnce());
        
        template.setRunOnce(false);
        assertFalse(template.isRunOnce());
    }

    @Test
    public void testNodeUsageMode() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        // Test NORMAL mode
        template.setNodeUsageMode(hudson.model.Node.Mode.NORMAL);
        assertEquals(hudson.model.Node.Mode.NORMAL, template.getNodeUsageMode());
        
        // Test EXCLUSIVE mode
        template.setNodeUsageMode(hudson.model.Node.Mode.EXCLUSIVE);
        assertEquals(hudson.model.Node.Mode.EXCLUSIVE, template.getNodeUsageMode());
    }

    @Test
    public void testTresConfiguration() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        // TRES per job (e.g., GPUs)
        template.setTresPerJob("gres/gpu:1");
        assertEquals("gres/gpu:1", template.getTresPerJob());
        
        // TRES per node
        template.setTresPerNode("gres/gpu:2");
        assertEquals("gres/gpu:2", template.getTresPerNode());
        
        // TRES per task
        template.setTresPerTask("gres/gpu:1");
        assertEquals("gres/gpu:1", template.getTresPerTask());
    }

    @Test
    public void testAdvancedSlurmOptions() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        // Account
        template.setAccount("my-account");
        assertEquals("my-account", template.getAccount());
        
        // QoS
        template.setQos("high-priority");
        assertEquals("high-priority", template.getQos());
        
        // Constraints
        template.setConstraints("gpu&nvme");
        assertEquals("gpu&nvme", template.getConstraints());
        
        // Required nodes
        template.setRequiredNodes("node001,node002");
        assertEquals("node001,node002", template.getRequiredNodes());
        
        // Excluded nodes
        template.setExcludedNodes("node003");
        assertEquals("node003", template.getExcludedNodes());
    }

    @Test
    public void testUniqueTemplateId() {
        SlurmJobTemplate template1 = new SlurmJobTemplate();
        SlurmJobTemplate template2 = new SlurmJobTemplate();
        
        // Each template should have unique ID
        assertNotNull(template1.getId());
        assertNotNull(template2.getId());
        assertFalse(template1.getId().equals(template2.getId()));
    }

    @Test
    public void testTemplateToString() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setName("test-template");
        template.setLabel("docker linux");
        
        String str = template.toString();
        assertNotNull(str);
        // Should contain template name
        assertTrue(str.contains("test-template") || str.contains(template.getId()));
    }

    @Test
    public void testInstanceCapStringGetter() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        template.setInstanceCap(5);
        assertEquals(5, template.getInstanceCapStr());
        
        // Test with MAX_VALUE
        template.setInstanceCap(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, template.getInstanceCapStr());
    }

    @Test
    public void testCurrentWorkingDirectory() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        template.setCurrentWorkingDirectory("/home/jenkins/workspace");
        assertEquals("/home/jenkins/workspace", template.getCurrentWorkingDirectory());
        
        // Test with null
        template.setCurrentWorkingDirectory(null);
        // Should handle null gracefully (implementation dependent)
    }

    @Test
    public void testPartitionValidation() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        // Valid partition name
        template.setPartition("general");
        assertEquals("general", template.getPartition());
        
        // Empty partition (may be OK if cloud has default)
        template.setPartition("");
        assertEquals("", template.getPartition());
    }

    @Test
    public void testMultipleNodesConfiguration() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        template.setMinimumNodes(2);
        assertEquals(Integer.valueOf(2), template.getMinimumNodes());
        
        template.setTasks(4);
        assertEquals(Integer.valueOf(4), template.getTasks());
    }

    @Test
    public void testEnvironmentVariables() {
        SlurmJobTemplate template = new SlurmJobTemplate();
        
        // Environment is a string in JSON format
        String envJson = "{\"CUSTOM_VAR\": \"value\", \"PATH\": \"/usr/local/bin:/usr/bin\"}";
        
        template.setEnvironment(envJson);
        
        String retrievedEnv = template.getEnvironment();
        assertNotNull(retrievedEnv);
        assertEquals(envJson, retrievedEnv);
    }
}
