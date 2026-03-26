package io.jenkins.plugins.slurm.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        String json = "{\"job\": {\"partition\": \"gpu\", \"cpus_per_task\": 16, \"memory_per_node\": 32768, \"time_limit\": 120, \"tres_per_job\": \"gres/gpu:gfx1030:1\"}}";
        step.setJson(json);

        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());

        assertNotNull(template);
        assertEquals("gpu", template.getPartition());
        assertEquals(Integer.valueOf(16), template.getCpusPerTask());
        assertEquals(Long.valueOf(32768), template.getMemoryPerNode());
    }

    @Test
    public void testJsonWithContainers() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        String json = "{\"job\": {\"partition\": \"gpu\", \"cpus_per_task\": 8}, \"pyxis\": {\"container_image\": \"nvcr.io/nvidia/pytorch:latest\", \"container_mounts\": \"/data:/data\", \"container_workdir\": \"/workspace\", \"container_mount_home\": true}}";
        step.setJson(json);

        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());

        assertNotNull(template);
        assertEquals("gpu", template.getPartition());
        assertEquals(Integer.valueOf(8), template.getCpusPerTask());
        assertNotNull(template.getPyxis());
        assertEquals("nvcr.io/nvidia/pytorch:latest", template.getPyxis().getContainerImage());
    }

    @Test
    public void testPropertiesOverrideJson() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();

        // Set JSON configuration
        String json = "{\"job\": {\"partition\": \"gpu\", \"cpus_per_task\": 8, \"memory_per_node\": 16384}}";
        step.setJson(json);

        // Set properties that should override JSON
        step.setCpus(16);        // overrides JSON value of 8
        step.setPartition("cpu"); // overrides JSON value of "gpu"

        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());

        assertNotNull(template);
        assertEquals("cpu", template.getPartition());
        assertEquals(Integer.valueOf(16), template.getCpusPerTask());
    }

    @Test
    public void testAdvancedJsonFields() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        String json = "{\"job\": {\"account\": \"project123\", \"qos\": \"high\", \"reservation\": \"gpu_res\", \"constraints\": \"volta\", \"minimum_nodes\": 2, \"tasks\": 4}}";
        step.setJson(json);

        SlurmJobTemplate template = step.buildJobTemplate(createTestCloud());

        assertNotNull(template);
        assertEquals("project123", template.getAccount());
        assertEquals("high", template.getQos());
        assertEquals("gpu_res", template.getReservation());
        assertEquals("volta", template.getConstraints());
        assertEquals(Integer.valueOf(4), template.getTasks());
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
    public void testInvalidJsonThrows() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        step.setJson("{invalid json");

        assertThrows(IllegalArgumentException.class, () -> step.buildJobTemplate(createTestCloud()));
    }

    @Test
    public void testJsonMissingJobKeyThrows() {
        SlurmJobTemplateStep step = new SlurmJobTemplateStep();
        // Flat format without the required "job" wrapper key
        step.setJson("{\"partition\": \"gpu\", \"cpus_per_task\": 8}");

        assertThrows(IllegalArgumentException.class, () -> step.buildJobTemplate(createTestCloud()));
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
