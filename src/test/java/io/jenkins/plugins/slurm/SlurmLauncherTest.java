package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.slurm.client.ApiException;
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
    void isLaunchSupported_trueForNewLauncher() {
        assertTrue(new SlurmLauncher().isLaunchSupported());
    }

    @Test
    void getProblem_defaultsToNull() {
        SlurmLauncher launcher = new SlurmLauncher();
        assertEquals(null, launcher.getProblem());
    }

    @Test
    void invalidStatusLine_isTransientEvenWhenTextContains400() {
        IOException statusLine = new IOException("Invalid status line: \"HTTP/0.9 400 BAD REQUEST\"");
        assertTrue(SlurmLauncher.isTransientSubmitFailure(statusLine));
        assertFalse(SlurmLauncher.isPermanentSubmitRejection(statusLine));
        assertTrue(SlurmLauncher.isTransientSubmitFailure(new ApiException(statusLine)));
        assertFalse(SlurmLauncher.isPermanentSubmitRejection(new ApiException(400, statusLine.getMessage())));
    }

    @Test
    void http5xxAndConnectionReset_areTransient() {
        assertTrue(SlurmLauncher.isTransientSubmitFailure(new ApiException(503, "slurmrestd unavailable")));
        assertTrue(SlurmLauncher.isTransientSubmitFailure(new ApiException(0, "no status")));
        assertTrue(SlurmLauncher.isTransientSubmitFailure(new IOException("Connection reset")));
        assertFalse(SlurmLauncher.isPermanentSubmitRejection(new ApiException(503, "slurmrestd unavailable")));
    }

    @Test
    void realHttp4xx_isPermanentRejection() {
        ApiException rejected = new ApiException(400, "Invalid partition name");
        assertFalse(SlurmLauncher.isTransientSubmitFailure(rejected));
        assertTrue(SlurmLauncher.isPermanentSubmitRejection(rejected));
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
