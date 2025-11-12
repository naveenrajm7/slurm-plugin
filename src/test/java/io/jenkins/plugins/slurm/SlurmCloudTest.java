package io.jenkins.plugins.slurm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import hudson.util.FormValidation;
import org.junit.Test;

/**
 * Tests for {@link SlurmCloud}.
 */
public class SlurmCloudTest {

    @Test
    public void testCloudDefaults() {
        SlurmCloud cloud = new SlurmCloud("test", "http://localhost:6820", null, "general", 10, 60);
        assertEquals("test", cloud.name);
        assertNotNull(cloud.getJobTemplates());
        assertEquals(0, cloud.getJobTemplates().size());
    }

    @Test
    public void testDescriptorValidation() {
        SlurmCloud.DescriptorImpl descriptor = new SlurmCloud.DescriptorImpl();
        
        // Valid URL
        FormValidation validation = descriptor.doCheckSlurmRestApiUrl("http://localhost:6820");
        assertEquals(FormValidation.Kind.OK, validation.kind);
        
        // Empty URL
        validation = descriptor.doCheckSlurmRestApiUrl("");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        
        // Invalid URL
        validation = descriptor.doCheckSlurmRestApiUrl("not-a-url");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
    }

    @Test
    public void testMaxAgentsValidation() {
        SlurmCloud.DescriptorImpl descriptor = new SlurmCloud.DescriptorImpl();
        
        // Valid value
        FormValidation validation = descriptor.doCheckMaxAgents("10");
        assertEquals(FormValidation.Kind.OK, validation.kind);
        
        // Negative value
        validation = descriptor.doCheckMaxAgents("-1");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        
        // Not a number
        validation = descriptor.doCheckMaxAgents("abc");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
    }
}
