package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.TaskListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
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
    void classify_missingQueueItemWhileBuildRuns_isHandoff(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                started.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
                return true;
            }
        });
        var build = project.scheduleBuild2(0).waitForStart();
        try {
            assertTrue(started.await(30, TimeUnit.SECONDS));
            assertTrue(build.isBuilding());
            assertEquals(
                    JobUtils.ProvisioningRelease.TAKEN_BY_ANOTHER_EXECUTOR,
                    JobUtils.classifyProvisioningRelease(null, build));
            assertEquals(JobUtils.ProvisioningRelease.BUILD_FINISHED, JobUtils.classifyProvisioningRelease(null, null));
        } finally {
            release.countDown();
        }
        j.waitForCompletion(build);
        assertEquals(JobUtils.ProvisioningRelease.BUILD_FINISHED, JobUtils.classifyProvisioningRelease(null, build));
    }

    @Test
    void labelMatches_exactName(JenkinsRule j) {
        Label label = Label.get("linux");
        assertTrue(JobUtils.labelMatches("linux", label));
    }
}
