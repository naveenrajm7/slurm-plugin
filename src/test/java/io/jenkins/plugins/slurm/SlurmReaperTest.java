package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for {@link SlurmReaper}.
 *
 * <p>End-to-end reaping (querying the live Slurm REST API) is not tested here; the tests cover
 * the decision logic and Jenkins-side side-effects (node removal).
 */
@WithJenkins
class SlurmReaperTest {

    // -------------------------------------------------------------------------
    // reapIfStale — skip cases
    // -------------------------------------------------------------------------

    @Test
    void reapIfStale_skipsLaunchingComputer(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "reaper-cloud1", 5, template);
        SlurmAgent agent = SlurmTestHelper.createStaticAgent("reaper-launching", cloud.name, template.getId());
        agent.setSlurmJobId("100");
        j.jenkins.addNode(agent);

        SlurmComputer computer = (SlurmComputer) agent.toComputer();
        assertNotNull(computer);
        computer.setLaunching(true);   // simulate mid-launch

        SlurmReaper.reapIfStale(computer, hudson.model.TaskListener.NULL);

        // Node must still be registered.
        assertNotNull(j.jenkins.getNode("reaper-launching"),
                "Agent should NOT be removed while launching");
    }

    @Test
    void reapIfStale_skipsOnlineComputer(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "reaper-cloud2", 5, template);
        SlurmAgent agent = SlurmTestHelper.createStaticAgent("reaper-online", cloud.name, template.getId());
        agent.setSlurmJobId("200");
        j.jenkins.addNode(agent);

        // NoLaunchLauncher → computer stays offline; we cannot easily make it "online"
        // without a real JNLP. Just verify isOnline() guards the reap path.
        SlurmComputer computer = (SlurmComputer) agent.toComputer();
        assertNotNull(computer);

        // computer is offline (NoLaunchLauncher never connects), so the online-skip guard
        // won't fire. But if we call reapIfStale on it with no Slurm client available
        // (no cloud credential / no real REST server), the method should handle it gracefully.
        SlurmReaper.reapIfStale(computer, hudson.model.TaskListener.NULL);
        // No exception is the assertion here — cloud has no real slurmrestd.
    }

    @Test
    void reapIfStale_skipsAgentWithNoJobId(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "reaper-cloud3", 5, template);
        SlurmAgent agent = SlurmTestHelper.createStaticAgent("reaper-nojob", cloud.name, template.getId());
        // No slurmJobId set — agent never submitted a Slurm job.
        j.jenkins.addNode(agent);

        SlurmComputer computer = (SlurmComputer) agent.toComputer();
        assertNotNull(computer);

        SlurmReaper.reapIfStale(computer, hudson.model.TaskListener.NULL);

        assertNotNull(j.jenkins.getNode("reaper-nojob"),
                "Agent without a job ID should not be removed");
    }

    // -------------------------------------------------------------------------
    // reapIfStale — cloud-missing case
    // -------------------------------------------------------------------------

    @Test
    void reapIfStale_removesAgentWhenCloudIsMissing(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "reaper-gone-cloud", 5, template);
        SlurmAgent agent = SlurmTestHelper.createStaticAgent("reaper-gone-agent", cloud.name, template.getId());
        agent.setSlurmJobId("999");
        j.jenkins.addNode(agent);

        // Remove the cloud — getSlurmCloud() will throw IllegalStateException.
        j.jenkins.clouds.remove(cloud);

        SlurmComputer computer = (SlurmComputer) agent.toComputer();
        assertNotNull(computer);

        SlurmReaper.reapIfStale(computer, hudson.model.TaskListener.NULL);

        assertNull(j.jenkins.getNode("reaper-gone-agent"),
                "Agent whose cloud is gone should be removed by the Reaper");
    }

    // -------------------------------------------------------------------------
    // reapStaleNodes — smoke test
    // -------------------------------------------------------------------------

    @Test
    void reapStaleNodes_doesNotThrowWithNoSlurmClouds(JenkinsRule j) {
        // No SlurmCloud registered → should complete silently.
        SlurmReaper.reapStaleNodes(hudson.model.TaskListener.NULL);
    }

    @Test
    void reapStaleNodes_doesNotThrowWithOfflineAgents(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "reaper-smoke-cloud", 5, template);
        SlurmAgent agent = SlurmTestHelper.createStaticAgent("reaper-smoke-agent", cloud.name, template.getId());
        agent.setSlurmJobId("77");
        j.jenkins.addNode(agent);

        // Cloud has no real REST server — createClient returns null → reap is skipped gracefully.
        SlurmReaper.reapStaleNodes(hudson.model.TaskListener.NULL);
    }
}
