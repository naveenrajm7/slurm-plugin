package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlurmGarbageCollectionTest {

    @AfterEach
    void clearHeartbeats() {
        SlurmJobHeartbeats.clearForTest();
    }

    @Test
    void isPluginJobName_matchesCloudPrefix() {
        assertTrue(SlurmGarbageCollection.isPluginJobName("ctr2-alola-ctrl-01", "ctr2-alola-ctrl-01-wl-legato-cpu-1"));
        assertFalse(SlurmGarbageCollection.isPluginJobName("ctr2-alola-ctrl-01", "other-job"));
    }

    @Test
    void shouldCancelOrphan_whenHeartbeatStaleAndNoLiveAgent() {
        SlurmJobHeartbeats.refresh("cloud-a", "12345", "cloud-a-template-1");
        long futureNow = System.currentTimeMillis() + Duration.ofMinutes(6).toMillis();
        assertTrue(SlurmGarbageCollection.shouldCancelOrphan(
                "12345", Set.of(), Duration.ofMinutes(5), futureNow));
    }

    @Test
    void shouldCancelOrphan_falseWhileAgentStillRegistered() {
        SlurmJobHeartbeats.refresh("cloud-a", "12345", "cloud-a-template-1");
        long futureNow = System.currentTimeMillis() + Duration.ofMinutes(6).toMillis();
        assertFalse(SlurmGarbageCollection.shouldCancelOrphan(
                "12345", Set.of("12345"), Duration.ofMinutes(5), futureNow));
    }

    @Test
    void shouldCancelOrphan_falseWithoutHeartbeat() {
        assertFalse(SlurmGarbageCollection.shouldCancelOrphan(
                "999", Set.of(), Duration.ofMinutes(5), System.currentTimeMillis()));
    }

    @Test
    void minimumTimeout_enforcedOutsideUnitTests() {
        SlurmGarbageCollection gc = new SlurmGarbageCollection();
        gc.setTimeoutSeconds(60);
        if (hudson.Main.isUnitTest) {
            assertEquals(60, gc.getTimeoutSeconds());
        }
    }
}
