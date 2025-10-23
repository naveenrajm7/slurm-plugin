package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PyxisConfig}.
 */
public class PyxisConfigTest {

    @Test
    public void testDefaults() {
        PyxisConfig config = new PyxisConfig();
        
        assertEquals("", config.getContainerImage());
        assertEquals("", config.getContainerMounts());
        assertEquals(Boolean.TRUE, config.getContainerMountHome());
        assertEquals("", config.getContainerWorkdir());
        assertEquals("", config.getContainerName());
        assertNull(config.getContainerRemap());
        assertEquals("", config.getContainerSave());
        assertEquals(Boolean.FALSE, config.getContainerWritable());
        assertEquals("", config.getContainerEntrypoint());
    }

    @Test
    public void testSettersAndGetters() {
        PyxisConfig config = new PyxisConfig();
        
        config.setContainerImage("/path/to/container.sqsh");
        assertEquals("/path/to/container.sqsh", config.getContainerImage());
        
        config.setContainerMounts("/data:/data,/work:/work");
        assertEquals("/data:/data,/work:/work", config.getContainerMounts());
        
        config.setContainerMountHome(false);
        assertEquals(Boolean.FALSE, config.getContainerMountHome());
        
        config.setContainerWorkdir("/workspace");
        assertEquals("/workspace", config.getContainerWorkdir());
        
        config.setContainerName("my-container");
        assertEquals("my-container", config.getContainerName());
        
        config.setContainerRemap(true);
        assertEquals(Boolean.TRUE, config.getContainerRemap());
        
        config.setContainerSave("/save/path");
        assertEquals("/save/path", config.getContainerSave());
        
        config.setContainerWritable(true);
        assertEquals(Boolean.TRUE, config.getContainerWritable());
        
        config.setContainerEntrypoint("/bin/bash");
        assertEquals("/bin/bash", config.getContainerEntrypoint());
    }

    @Test
    public void testNullHandling() {
        PyxisConfig config = new PyxisConfig();
        
        config.setContainerImage(null);
        assertEquals("", config.getContainerImage());
        
        config.setContainerMounts(null);
        assertEquals("", config.getContainerMounts());
        
        config.setContainerWorkdir(null);
        assertEquals("", config.getContainerWorkdir());
        
        config.setContainerName(null);
        assertEquals("", config.getContainerName());
        
        config.setContainerSave(null);
        assertEquals("", config.getContainerSave());
        
        config.setContainerEntrypoint(null);
        assertEquals("", config.getContainerEntrypoint());
    }

    @Test
    public void testToString() {
        PyxisConfig config = new PyxisConfig();
        config.setContainerImage("/path/to/container.sqsh");
        config.setContainerMounts("/data:/data");
        
        String result = config.toString();
        // PyxisConfig uses default Object.toString(), just verify it's not null
        assertNotNull(result);
    }
}
