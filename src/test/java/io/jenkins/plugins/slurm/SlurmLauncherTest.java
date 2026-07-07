package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.slurm.client.SlurmClient;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class SlurmLauncherTest {

    @Test
    void setProblem_storesThrowable() {
        SlurmLauncher launcher = new SlurmLauncher();
        IOException failure = new IOException("previous failure");
        launcher.setProblem(failure);
        assertSame(failure, launcher.getProblem());
    }

    @Test
    void getProblem_defaultsToNull() {
        SlurmLauncher launcher = new SlurmLauncher();
        assertEquals(null, launcher.getProblem());
    }

    @Test
    void isFailedState_recognizesTerminalStates() {
        assertTrue(SlurmClient.isFailedState("FAILED"));
        assertTrue(SlurmClient.isFailedState("CANCELLED"));
        assertTrue(SlurmClient.isFailedState("TIMEOUT"));
        assertTrue(SlurmClient.isFailedState("NODE_FAIL"));
        assertTrue(SlurmClient.isFailedState("OUT_OF_MEMORY"));
        assertFalse(SlurmClient.isFailedState("RUNNING"));
        assertFalse(SlurmClient.isFailedState("PENDING"));
        assertFalse(SlurmClient.isFailedState(null));
    }
}
