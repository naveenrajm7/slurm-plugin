package io.jenkins.plugins.slurm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlurmJobStatusTest {

    @Test
    void formatForDisplay_includesStateAndReason() {
        SlurmJobStatus status = new SlurmJobStatus("413835", "PENDING", "Priority", null);
        assertEquals("Slurm job 413835: PENDING (Priority)", status.formatForDisplay());
        assertEquals("[Slurm] Slurm job 413835: PENDING (Priority)", status.formatForConsole());
    }

    @Test
    void formatForDisplay_missingJob() {
        SlurmJobStatus status = new SlurmJobStatus("99", null, null, null);
        assertTrue(status.isMissing());
        assertEquals("Slurm job 99: not found (may have been cancelled)", status.formatForDisplay());
    }

    @Test
    void formatForDisplay_omitsNoneReason() {
        SlurmJobStatus status = new SlurmJobStatus("1", "PENDING", "None", null);
        assertEquals("Slurm job 1: PENDING", status.formatForDisplay());
    }

    @Test
    void formatForDisplay_includesNodesWhenPresent() {
        SlurmJobStatus status = new SlurmJobStatus("42", "RUNNING", null, "cgy-absol");
        assertEquals("Slurm job 42: RUNNING on cgy-absol", status.formatForDisplay());
        assertEquals("[Slurm] Slurm job 42: RUNNING on cgy-absol", status.formatForConsole());
    }

    @Test
    void formatConnectionMessage_includesComputeNodes() {
        SlurmJobStatus status = new SlurmJobStatus("42", "RUNNING", null, "cgy-absol");
        assertEquals("[Slurm] Slurm job 42 on node(s) cgy-absol", status.formatConnectionMessage());
    }

    @Test
    void formatConnectionMessage_withoutNodes() {
        SlurmJobStatus status = new SlurmJobStatus("42", "RUNNING", null, null);
        assertEquals(
                "[Slurm] Slurm job 42 connected (compute node not reported by Slurm yet)",
                status.formatConnectionMessage());
    }
}
