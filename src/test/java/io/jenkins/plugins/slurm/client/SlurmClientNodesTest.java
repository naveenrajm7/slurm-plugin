package io.jenkins.plugins.slurm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.jenkins.plugins.slurm.client.model.JobInfo;
import io.jenkins.plugins.slurm.client.model.JobRes;
import io.jenkins.plugins.slurm.client.model.JobResNode;
import io.jenkins.plugins.slurm.client.model.JobResNodes1;
import org.junit.jupiter.api.Test;

class SlurmClientNodesTest {

    @Test
    void resolveAllocatedNodes_prefersTopLevelNodes() {
        JobInfo jobInfo = new JobInfo().nodes("node-a,node-b");
        assertEquals("node-a,node-b", SlurmClient.resolveAllocatedNodes(jobInfo));
    }

    @Test
    void resolveAllocatedNodes_fallsBackToJobResourcesList() {
        JobResNodes1 nodes = new JobResNodes1()._list("cgy-absol");
        JobRes resources = new JobRes().nodes(nodes);
        JobInfo jobInfo = new JobInfo().jobResources(resources);

        assertEquals("cgy-absol", SlurmClient.resolveAllocatedNodes(jobInfo));
    }

    @Test
    void resolveAllocatedNodes_fallsBackToAllocationNames() {
        JobResNodes1 nodes = new JobResNodes1().allocation(java.util.List.of(new JobResNode().name("cgy-absol")));
        JobRes resources = new JobRes().nodes(nodes);
        JobInfo jobInfo = new JobInfo().jobResources(resources);

        assertEquals("cgy-absol", SlurmClient.resolveAllocatedNodes(jobInfo));
    }

    @Test
    void resolveAllocatedNodes_returnsNullWhenMissing() {
        assertNull(SlurmClient.resolveAllocatedNodes(new JobInfo()));
        assertNull(SlurmClient.resolveAllocatedNodes(null));
    }
}
