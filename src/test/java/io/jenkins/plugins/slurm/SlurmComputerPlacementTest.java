package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SlurmComputerPlacementTest {

    @Test
    void formatPlacementSummary_includesComputeNodesLabel() {
        assertEquals(
                "Job ID: 99, Partition: jenkins-e2e, Compute node(s): node01",
                SlurmComputer.formatPlacementSummary("99", "jenkins-e2e", "node01"));
    }

    @Test
    void formatPlacementSummary_handlesMissingFields() {
        assertEquals("Job ID: Not submitted", SlurmComputer.formatPlacementSummary(null, null, null));
    }
}
