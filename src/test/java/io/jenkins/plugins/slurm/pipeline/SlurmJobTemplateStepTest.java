package io.jenkins.plugins.slurm.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jenkins.plugins.slurm.SlurmCloud;
import io.jenkins.plugins.slurm.SlurmJobTemplate;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SlurmJobTemplateStep} focusing on JSON configuration.
 */
public class SlurmJobTemplateStepTest {

    private SlurmCloud createTestCloud() {
        return new SlurmCloud("test", "http://localhost:6820", null, "general", 10, 60);
    }

    @Test
    public void testBasicTemplate() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        step.setPartition("gpu");
        step.setCpus(16);
        step.setMemory("32G");
        step.setTime("02:00:00");
        
        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());
        
        assertNotNull(template);
        assertEquals("gpu", template.getPartition());
        assertEquals(Integer.valueOf(16), template.getCpusPerTask());
    }

    @Test
    public void testJsonConfiguration() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        String json = "{\"partition\": \"gpu\", \"cpus\": 16, \"memory\": \"32G\", \"time\": \"02:00:00\", \"gres\": \"gpu:gfx1030:1\"}";
        step.setJson(json);
        
        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());
        
        assertNotNull(template);
        assertEquals("gpu", template.getPartition());
        // Note: JSON parsing in buildJobTemplate will set these fields
    }

    @Test
    public void testJsonWithContainers() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        String json = "{\"partition\": \"gpu\", \"cpus\": 8, \"containerImage\": \"nvcr.io/nvidia/pytorch:latest\", \"containerMounts\": \"/data:/data\", \"containerWorkdir\": \"/workspace\", \"containerMountHome\": true}";
        step.setJson(json);
        
        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());
        
        assertNotNull(template);
        assertEquals("gpu", template.getPartition());
        // Container configuration will be parsed from JSON
    }

    @Test
    public void testPropertiesOverrideJson() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        
        // Set JSON configuration
        String json = "{\"partition\": \"gpu\", \"cpus\": 8, \"memory\": \"16G\"}";
        step.setJson(json);
        
        // Set properties that should override JSON
        step.setCpus(16); // Should override JSON value of 8
        step.setPartition("cpu"); // Should override JSON value of "gpu"
        
        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());
        
        assertNotNull(template);
        // Properties override JSON values
        assertEquals("cpu", template.getPartition());
        assertEquals(Integer.valueOf(16), template.getCpusPerTask());
    }

    @Test
    public void testAdvancedJsonFields() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        String json = "{\"account\": \"project123\", \"qos\": \"high\", \"reservation\": \"gpu_res\", \"constraints\": \"volta\", \"nodes\": \"2\", \"tasks\": 4}";
        step.setJson(json);
        
        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());
        
        assertNotNull(template);
        // These advanced fields should be parsed from JSON
    }

    @Test
    public void testEmptyJson() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        step.setJson("");
        step.setCpus(4);
        
        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());
        
        assertNotNull(template);
        assertEquals(Integer.valueOf(4), template.getCpusPerTask());
    }

    @Test
    public void testNullJson() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        step.setJson(null);
        step.setCpus(4);
        
        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());
        
        assertNotNull(template);
        assertEquals(Integer.valueOf(4), template.getCpusPerTask());
    }

    @Test
    public void testInvalidJsonFallsBackToProperties() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        step.setJson("{invalid json");
        step.setCpus(8);
        step.setPartition("gpu");
        
        // Even with invalid JSON, properties should work
        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());
        
        assertNotNull(template);
        assertEquals("gpu", template.getPartition());
        assertEquals(Integer.valueOf(8), template.getCpusPerTask());
    }

    @Test
    public void testInheritFrom() {
        // Create a parent template
        SlurmJobTemplate parentTemplate = new SlurmJobTemplate();
        parentTemplate.setName("parent");
        parentTemplate.setPartition("gpu");
        parentTemplate.setCpusPerTask(8);
        
        // Create a cloud with this template
        SlurmCloud cloud = createTestCloud();
        cloud.setJobTemplates(java.util.Collections.singletonList(parentTemplate));
        
        // Create a step that inherits from parent
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        step.setInheritFrom("parent");
        step.setCpus(16); // Override parent's cpus
        
        SlurmJobTemplate template = step.buildJobTemplate(cloud);
        
        assertNotNull(template);
        assertEquals("gpu", template.getPartition()); // Inherited
        assertEquals(Integer.valueOf(16), template.getCpusPerTask()); // Overridden
    }
}
