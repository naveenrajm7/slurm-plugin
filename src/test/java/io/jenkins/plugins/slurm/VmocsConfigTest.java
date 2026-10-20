package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link VmocsConfig}. */
public class VmocsConfigTest {

    @Test
    public void testDefaults() {
        VmocsConfig config = new VmocsConfig();
        assertEquals("", config.getVmImage());
        assertEquals("java", config.getJavaPath());
        assertEquals("", config.getJarPath());
        assertEquals("/tmp/jenkins", config.getWorkDir());
        assertEquals("", config.getForwardEnv());
        assertFalse(config.isConfigured());
        assertFalse(config.hasPreinstalledJar());
        assertTrue(config.customForwardEnvironmentNames().isEmpty());
    }

    @Test
    public void testTemplateNameIsRequired() {
        VmocsConfig config = new VmocsConfig();
        config.setJavaPath("/usr/bin/java");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(error.getMessage().contains("template name"));
    }

    @Test
    public void testRejectsContainerReference() {
        VmocsConfig config = configured();
        config.setVmImage("docker://jenkins/inbound-agent");
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    public void testAcceptsTemplateName() {
        VmocsConfig config = configured();
        config.validate();
        assertTrue(config.isConfigured());
    }

    @Test
    public void testJarAndWorkDirectoryMustBeAbsolute() {
        VmocsConfig relativeJar = configured();
        relativeJar.setJarPath("agent.jar");
        assertThrows(IllegalArgumentException.class, relativeJar::validate);

        VmocsConfig relativeWork = configured();
        relativeWork.setWorkDir("jenkins");
        assertThrows(IllegalArgumentException.class, relativeWork::validate);
    }

    @Test
    public void testForwardListRejectsReservedDuplicateAndOverflow() {
        VmocsConfig reserved = configured();
        reserved.setForwardEnv("FOO,JENKINS_SECRET");
        assertThrows(IllegalArgumentException.class, reserved::customForwardEnvironmentNames);

        VmocsConfig duplicate = configured();
        duplicate.setForwardEnv("FOO, FOO");
        assertThrows(IllegalArgumentException.class, duplicate::customForwardEnvironmentNames);

        VmocsConfig overflow = configured();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            if (i > 0) {
                names.append(',');
            }
            names.append("VAR").append(i);
        }
        overflow.setForwardEnv(names.toString());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, overflow::validate);
        assertTrue(error.getMessage().contains("16"));
    }

    @Test
    public void testForwardListParsesNames() {
        VmocsConfig config = configured();
        config.setForwardEnv(" FOO, BAR ");
        assertEquals(List.of("FOO", "BAR"), config.customForwardEnvironmentNames());
    }

    private static VmocsConfig configured() {
        VmocsConfig config = new VmocsConfig();
        config.setVmImage("base-ubuntu");
        return config;
    }
}
