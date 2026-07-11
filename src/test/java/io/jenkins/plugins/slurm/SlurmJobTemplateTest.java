package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.util.FormValidation;
import org.junit.jupiter.api.Test;

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
}
