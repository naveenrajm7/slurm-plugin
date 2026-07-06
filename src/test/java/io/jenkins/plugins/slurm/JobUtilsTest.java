package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertSame;

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
}
