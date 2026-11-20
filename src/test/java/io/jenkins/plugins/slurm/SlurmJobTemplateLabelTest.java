package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for {@link SlurmJobTemplate} label matching (Jenkins label expressions).
 */
@WithJenkins
public class SlurmJobTemplateLabelTest {

    @Test
    void canTake_ckNogpuExpression(JenkinsRule j) {
        SlurmJobTemplate t = new SlurmJobTemplate();
        t.setLabel("rocmtest nogpu");

        assertEquals(2, t.getLabelAtoms().size());
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

    @Test
    void getAgentLabelString_splitsCompoundLabel(JenkinsRule j) {
        SlurmJobTemplate t = new SlurmJobTemplate();
        t.setLabel("legato-compile ubuntu24-therrock");
        assertEquals("legato-compile ubuntu24-therrock", t.getAgentLabelString());
        assertEquals(2, t.getLabelAtoms().size());
    }

    @Test
    void canTake_ignoresCollapsedLabelAtom(JenkinsRule j) {
        String labels = "navi32 rocmtest gfx1101 miopen";
        // Registers the whole string as one atom, which is what Jenkins.getLabel does.
        j.jenkins.getLabel(labels);

        SlurmJobTemplate t = new SlurmJobTemplate();
        t.setLabel(labels);

        assertEquals(4, t.getLabelAtoms().size());
        assertTrue(t.canTake("(rocmtest||migraphx)&&gfx1101&&!vm"));
        assertTrue(t.canTake("miopen gfx1101"));
        assertTrue(JobUtils.labelMatches(labels, Label.parseExpression("(rocmtest||migraphx)&&gfx1101&&!vm")));
        assertTrue(JobUtils.labelMatches(t.getAgentLabelString(), Label.parseExpression("miopen&&(gfx1101||gfx1100)")));
    }
}
