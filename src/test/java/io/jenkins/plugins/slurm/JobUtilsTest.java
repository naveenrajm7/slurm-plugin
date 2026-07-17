package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.TaskListener;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class JobUtilsTest {

    @Test
    void findRunListenerForLabel_returnsNullListenerWhenQueueEmpty(JenkinsRule j) {
        TaskListener listener = JobUtils.findRunListenerForLabel("no-such-label");
        assertSame(TaskListener.NULL, listener);
    }

    @Test
    void findRunListenerForLabel_returnsNullListenerForBlankLabel(JenkinsRule j) {
        TaskListener listener = JobUtils.findRunListenerForLabel("   ");
        assertSame(TaskListener.NULL, listener);
    }

    @Test
    void labelMatches_compoundExpression(JenkinsRule j) {
        Label pipelineLabel = Label.parseExpression("legato-compile && ubuntu24-therrock");
        assertTrue(JobUtils.labelMatches("legato-compile ubuntu24-therrock", pipelineLabel));
    }

    @Test
    void labelMatches_exactName(JenkinsRule j) {
        Label label = Label.get("linux");
        assertTrue(JobUtils.labelMatches("linux", label));
    }
}
