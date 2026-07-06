package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for {@link SlurmJobTemplate} label matching (CK-style expressions).
 */
@WithJenkins
public class SlurmJobTemplateLabelTest {

    @Test
    void canTake_ckNogpuExpression(JenkinsRule j) {
        SlurmJobTemplate t = new SlurmJobTemplate();
        t.setLabel("rocmtest nogpu");

        assertTrue(t.canTake("(rocmtest || miopen) && nogpu"));
        assertTrue(t.canTake("rocmtest nogpu"));
        assertFalse(t.canTake("(rocmtest || miopen) && gfx942"));
    }

    @Test
    void canTake_gfx942Expression(JenkinsRule j) {
        SlurmJobTemplate t = new SlurmJobTemplate();
        t.setLabel("rocmtest gfx942");

        assertTrue(t.canTake("(rocmtest || miopen) && gfx942"));
        assertFalse(t.canTake("(rocmtest || miopen) && nogpu"));
    }

    @Test
    void canTake_miopenTemplate(JenkinsRule j) {
        SlurmJobTemplate t = new SlurmJobTemplate();
        t.setLabel("miopen gfx942");

        assertTrue(t.canTake("(rocmtest || miopen) && gfx942"));
    }
}
