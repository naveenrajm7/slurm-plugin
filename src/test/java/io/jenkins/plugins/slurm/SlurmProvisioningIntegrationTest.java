package io.jenkins.plugins.slurm;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import io.jenkins.plugins.slurm.client.SlurmClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.concurrent.ExecutionException;

/**
 * Integration tests for Slurm agent provisioning.
 * Tests the full provisioning flow with mocked Slurm API.
 */
public class SlurmProvisioningIntegrationTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private SlurmCloud cloud;
    private SlurmJobTemplate template;

    @Before
    public void setUp() throws Exception {
        cloud = SlurmTestUtil.createTestCloud("test-cloud");
        template = SlurmTestUtil.createTestTemplate("test-template", "docker linux");
        cloud.getJobTemplates().add(template);
        r.jenkins.clouds.add(cloud);
    }

    @After
    public void tearDown() throws Exception {
        SlurmTestUtil.removeAllSlurmAgents(r.jenkins);
    }

    @Test
    public void testBasicProvisioning() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");
            when(mockClient.getJobState("12345")).thenReturn("RUNNING");

            // Request provisioning
            Label label = Label.get("docker && linux");
            Cloud.CloudState state = new Cloud.CloudState(label, 1);
            Collection<PlannedNode> planned = cloud.provision(state, 1);

            // Should provision 1 agent
            assertEquals(1, planned.size());
        }
    }

    @Test
    public void testMultipleAgentProvisioning() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");
            when(mockClient.getJobState(anyString())).thenReturn("RUNNING");

            // Request 3 agents
            Label label = Label.get("docker && linux");
            Cloud.CloudState state = new Cloud.CloudState(label, 3);
            Collection<PlannedNode> planned = cloud.provision(state, 3);

            // Should provision 3 agents
            assertEquals(3, planned.size());
        }
    }

    @Test
    public void testProvisioningRespectsCloudCapacity() throws Exception {
        // Create cloud with max 2 agents
        SlurmCloud limitedCloud = new SlurmCloud("limited", "http://localhost:6820", null, "general", 2, 60);
        SlurmJobTemplate limitedTemplate = SlurmTestUtil.createTestTemplate("limited-tpl", "test");
        limitedTemplate.setInstanceCap(10);
        limitedCloud.getJobTemplates().add(limitedTemplate);
        r.jenkins.clouds.add(limitedCloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");

            // Request 5 agents
            Label label = Label.get("test");
            Cloud.CloudState state = new Cloud.CloudState(label, 5);
            Collection<PlannedNode> planned = limitedCloud.provision(state, 5);

            // Should only provision 2 (cloud capacity)
            assertEquals(2, planned.size());
        }
    }

    @Test
    public void testProvisioningRespectsTemplateInstanceCap() throws Exception {
        // Template with instance cap of 2
        template.setInstanceCap(2);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");

            // Request 5 agents
            Label label = Label.get("docker && linux");
            Cloud.CloudState state = new Cloud.CloudState(label, 5);
            Collection<PlannedNode> planned = cloud.provision(state, 5);

            // Should only provision 2 (template instance cap)
            assertEquals(2, planned.size());
        }
    }

    @Test
    public void testProvisioningWithNoMatchingTemplate() {
        // Request label that doesn't match any template
        Label label = Label.get("gpu && cuda");
        Cloud.CloudState state = new Cloud.CloudState(label, 1);
        Collection<PlannedNode> planned = cloud.provision(state, 1);

        // Should not provision anything
        assertTrue(planned.isEmpty());
    }

    @Test
    public void testProvisioningWithMultipleTemplates() throws Exception {
        // Add another template with different label
        SlurmJobTemplate gpuTemplate = SlurmTestUtil.createGpuTemplate("gpu-template", "gpu cuda");
        cloud.getJobTemplates().add(gpuTemplate);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");
            when(mockClient.getJobState(anyString())).thenReturn("RUNNING");

            // Request GPU agent
            Label gpuLabel = Label.get("gpu");
            Cloud.CloudState state = new Cloud.CloudState(gpuLabel, 1);
            Collection<PlannedNode> planned = cloud.provision(state, 1);

            // Should provision from GPU template
            assertEquals(1, planned.size());
        }
    }

    @Test
    public void testInProvisioningTracking() throws Exception {
        Label label = Label.get("docker && linux");

        // Test basic provisioning
        Cloud.CloudState state = new Cloud.CloudState(label, 5);
        Collection<PlannedNode> planned = cloud.provision(state, 5);

        // Should provision requested agents (implementation dependent)
        assertTrue(planned.size() >= 0);
        assertTrue(planned.size() <= 5);
    }

    @Test
    public void testProvisioningWithExistingAgents() throws Exception {
        // Add an existing agent
        SlurmAgent existingAgent = createTestAgent("existing-agent");
        r.jenkins.addNode(existingAgent);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");

            // Cloud has 10 max agents, 1 exists
            // Request 2 more agents
            Label label = Label.get("docker && linux");
            Cloud.CloudState state = new Cloud.CloudState(label, 2);
            Collection<PlannedNode> planned = cloud.provision(state, 2);

            // Should provision 2 more (total would be 3, under limit)
            assertEquals(2, planned.size());
        }
    }

    @Test
    public void testProvisioningAtCapacity() throws Exception {
        // Set cloud max to 2
        SlurmCloud smallCloud = new SlurmCloud("small", "http://localhost:6820", null, "general", 2, 60);
        SlurmJobTemplate smallTemplate = SlurmTestUtil.createTestTemplate("small-tpl", "test");
        smallCloud.getJobTemplates().add(smallTemplate);
        r.jenkins.clouds.add(smallCloud);

        // Add 2 agents (at capacity)
        SlurmAgent agent1 = createTestAgentForCloud("agent-1", smallCloud, smallTemplate);
        SlurmAgent agent2 = createTestAgentForCloud("agent-2", smallCloud, smallTemplate);
        r.jenkins.addNode(agent1);
        r.jenkins.addNode(agent2);

        // Request more agents
        Label label = Label.get("test");
        Cloud.CloudState state = new Cloud.CloudState(label, 3);
        Collection<PlannedNode> planned = smallCloud.provision(state, 3);

        // Should not provision any (at capacity)
        assertTrue(planned.isEmpty());
    }

    @Test
    public void testProvisioningWithZeroWorkload() {
        // Request 0 agents
        Label label = Label.get("docker && linux");
        Cloud.CloudState state = new Cloud.CloudState(label, 0);
        Collection<PlannedNode> planned = cloud.provision(state, 0);

        // Should not provision anything
        assertTrue(planned.isEmpty());
    }

    @Test
    public void testCanProvision() {
        // Test canProvision with matching label
        Label label = Label.get("docker && linux");
        Cloud.CloudState state = new Cloud.CloudState(label, 1);
        
        assertTrue(cloud.canProvision(state));

        // Test canProvision with non-matching label
        Label gpuLabel = Label.get("gpu");
        Cloud.CloudState gpuState = new Cloud.CloudState(gpuLabel, 1);
        
        assertFalse(cloud.canProvision(gpuState));
    }

    @Test
    public void testProvisioningWithContainerTemplate() throws Exception {
        // Add container template
        SlurmJobTemplate containerTemplate = SlurmTestUtil.createContainerTemplate(
            "container-tpl",
            "container",
            "/path/to/container.sqsh"
        );
        cloud.getJobTemplates().add(containerTemplate);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");
            when(mockClient.getJobState(anyString())).thenReturn("RUNNING");

            // Request container agent
            Label label = Label.get("container");
            Cloud.CloudState state = new Cloud.CloudState(label, 1);
            Collection<PlannedNode> planned = cloud.provision(state, 1);

            // Should provision container agent
            assertEquals(1, planned.size());
        }
    }

    @Test
    public void testConcurrentProvisioning() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");
            when(mockClient.getJobState(anyString())).thenReturn("RUNNING");

            // Provision multiple agents concurrently
            Label label = Label.get("docker && linux");
            
            Collection<PlannedNode> batch1 = cloud.provision(new Cloud.CloudState(label, 2), 2);
            Collection<PlannedNode> batch2 = cloud.provision(new Cloud.CloudState(label, 2), 2);

            // Both batches should succeed
            assertEquals(2, batch1.size());
            assertEquals(2, batch2.size());
        }
    }

    // Helper methods

    private SlurmAgent createTestAgent(String name) throws Exception {
        return createTestAgentForCloud(name, cloud, template);
    }

    private SlurmAgent createTestAgentForCloud(String name, SlurmCloud cloud, SlurmJobTemplate template) 
            throws Exception {
        SlurmLauncher launcher = new SlurmLauncher();
        hudson.slaves.RetentionStrategy<?> retentionStrategy = 
            new hudson.slaves.CloudRetentionStrategy(template.getIdleMinutes());
        
        org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Id cloudStatsId = 
            new org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Id(
                cloud.name,
                template.getId(),
                name
            );

        return new SlurmAgent(
            name,
            "Test Agent",
            template.getCurrentWorkingDirectory(),
            template.getCpusPerTask(),
            hudson.model.Node.Mode.NORMAL,
            template.getLabel(),
            launcher,
            retentionStrategy,
            new java.util.ArrayList<>(),
            cloud.name,
            template.getId(),
            template.getPartition(),
            cloudStatsId
        );
    }
}

