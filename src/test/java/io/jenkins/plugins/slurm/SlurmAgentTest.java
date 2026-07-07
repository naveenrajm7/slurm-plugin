package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlurmAgentTest {

    @Test
    void terminate_withoutJobId_skipsCancellation(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "agent-cloud", 10, template);
        SlurmAgent agent = SlurmTestHelper.createAgent("agent-no-job", cloud.name, template.getId());
        j.jenkins.addNode(agent);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        agent._terminate(new hudson.util.StreamTaskListener(output));

        assertTrue(output.toString().contains("No Slurm job ID"));
    }

    @Test
    void terminate_missingCloud_doesNotThrow(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "ephemeral-cloud", 10, template);
        SlurmAgent agent = SlurmTestHelper.createAgent("agent-missing-cloud", cloud.name, template.getId());
        agent.setSlurmJobId("424242");
        j.jenkins.addNode(agent);
        j.jenkins.clouds.remove(cloud);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        agent._terminate(new hudson.util.StreamTaskListener(output));

        assertTrue(output.toString().contains("Cloud no longer exists"));
    }

    @Test
    void terminate_keepJobOnFailure_skipsCancellation(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        template.setKeepJobOnFailure(true);
        SlurmCloud cloud = SlurmTestHelper.createCloud("keep-cloud", 10);
        cloud.setJobTemplates(Collections.singletonList(template));
        j.jenkins.clouds.add(cloud);

        SlurmAgent agent = SlurmTestHelper.createAgent("agent-keep-job", cloud.name, template.getId());
        agent.setSlurmJobId("777777");
        j.jenkins.addNode(agent);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        agent._terminate(new hudson.util.StreamTaskListener(output));

        String log = output.toString();
        assertTrue(log.contains("Keeping Slurm job running"));
        assertFalse(log.contains("Slurm job cancelled successfully"));
    }

    @Test
    void terminate_marksLauncherProblem(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "launcher-mark-cloud", 10, template);
        SlurmAgent agent = SlurmTestHelper.createAgent("agent-launcher-mark", cloud.name, template.getId());
        j.jenkins.addNode(agent);

        agent._terminate(new hudson.util.StreamTaskListener(new ByteArrayOutputStream()));

        SlurmLauncher launcher = (SlurmLauncher) agent.getComputer().getLauncher();
        assertTrue(launcher.getProblem() != null);
    }
}
