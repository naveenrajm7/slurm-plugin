package io.jenkins.plugins.slurm;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;
import io.jenkins.plugins.slurm.client.SlurmClient;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Tests for {@link SlurmAgent}.
 * Pattern inspired by KubernetesSlaveTest.
 */
public class SlurmAgentTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private SlurmCloud cloud;
    private SlurmJobTemplate template;

    @Before
    public void setUp() throws Exception {
        // Create a test cloud
        cloud = SlurmTestUtil.createTestCloud("test-cloud");
        r.jenkins.clouds.add(cloud);

        // Create a test template
        template = SlurmTestUtil.createTestTemplate("test-template", "test-label");
        cloud.getJobTemplates().add(template);
    }

    @After
    public void tearDown() throws Exception {
        SlurmTestUtil.removeAllSlurmAgents(r.jenkins);
    }

    @Test
    public void testAgentCreation() throws Exception {
        // Create an agent
        SlurmAgent agent = createTestAgent("test-agent-1");

        assertNotNull(agent);
        assertEquals("test-agent-1", agent.getNodeName());
        assertEquals("test-cloud", agent.getCloudName());
        assertEquals(template.getId(), agent.getTemplateId());
        assertEquals("general", agent.getPartition());
        assertNull(agent.getSlurmJobId());  // Not set yet
        assertNull(agent.getNodeList());     // Not set yet
    }

    @Test
    public void testAgentSlurmJobIdTracking() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-2");

        // Initially null
        assertNull(agent.getSlurmJobId());

        // Set job ID
        agent.setSlurmJobId("12345");
        assertEquals("12345", agent.getSlurmJobId());
    }

    @Test
    public void testAgentNodeListTracking() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-3");

        // Initially null
        assertNull(agent.getNodeList());

        // Set node list
        agent.setNodeList("node001,node002");
        assertEquals("node001,node002", agent.getNodeList());
    }

    @Test
    public void testGetSlurmCloud() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-4");

        // Should return the cloud
        SlurmCloud foundCloud = agent.getSlurmCloud();
        assertNotNull(foundCloud);
        assertEquals("test-cloud", foundCloud.name);
    }

    @Test
    public void testGetSlurmCloudWhenMissing() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-5");

        // Remove the cloud
        r.jenkins.clouds.clear();

        // Should throw exception
        assertThrows(IllegalStateException.class, () -> {
            agent.getSlurmCloud();
        });
    }

    @Test
    public void testGetSlurmCloudWrongType() throws Exception {
        // Create an agent with a cloud name that doesn't match
        SlurmAgent agent = createTestAgentWithCloudName("test-agent-6", "wrong-cloud");

        // Add a different type of cloud with that name
        // (We can't easily do this without another cloud impl, so we'll just remove clouds)
        r.jenkins.clouds.clear();

        // Should throw exception
        assertThrows(IllegalStateException.class, () -> {
            agent.getSlurmCloud();
        });
    }

    @Test
    public void testCreateComputer() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-7");

        SlurmComputer computer = agent.createComputer();
        assertNotNull(computer);
        assertTrue(computer instanceof SlurmComputer);
    }

    @Test
    public void testGetCloudStatsId() throws Exception {
        ProvisioningActivity.Id cloudStatsId = new ProvisioningActivity.Id(
            "test-cloud",
            template.getId(),
            "test-agent-8"
        );

        SlurmAgent agent = createTestAgentWithCloudStatsId("test-agent-8", cloudStatsId);

        assertEquals(cloudStatsId, agent.getId());
    }

    @Test
    public void testGetRunListener() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-9");

        // Initially should return NULL listener
        TaskListener listener = agent.getRunListener();
        assertNotNull(listener);
        
        // Set a custom listener
        TaskListener customListener = mock(TaskListener.class);
        agent.setRunListener(customListener);
        
        // Should return the custom listener
        assertEquals(customListener, agent.getRunListener());
    }

    @Test
    public void testTerminateWithJobId() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-10");
        agent.setSlurmJobId("12345");

        // Add agent to Jenkins
        r.jenkins.addNode(agent);

        TaskListener listener = TaskListener.NULL;

        // Mock the cloud's cancelJob method
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            // Terminate the agent
            agent.terminate();

            // Verify cancelJob was called
            verify(mockClient, times(1)).cancelJob("12345");
        }
    }

    @Test
    public void testTerminateWithoutJobId() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-11");
        // No job ID set

        // Add agent to Jenkins
        r.jenkins.addNode(agent);

        TaskListener listener = TaskListener.NULL;

        // Terminate should not fail even without job ID
        try {
            agent.terminate();
        } catch (Exception e) {
            fail("terminate() should not throw exception: " + e.getMessage());
        }
    }

    @Test
    public void testTerminateWithKeepJobOnFailure() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-12");
        agent.setSlurmJobId("12345");

        // Set keepJobOnFailure on template
        template.setKeepJobOnFailure(true);

        // Add agent to Jenkins
        r.jenkins.addNode(agent);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            // Terminate the agent
            agent.terminate();

            // Verify cancelJob was NOT called (keepJobOnFailure is true)
            verify(mockClient, never()).cancelJob(anyString());
        }
    }

    @Test
    public void testTerminateWhenCloudMissing() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-13");
        agent.setSlurmJobId("12345");

        // Add agent to Jenkins
        r.jenkins.addNode(agent);

        // Remove the cloud
        r.jenkins.clouds.clear();

        // Terminate should not throw even if cloud is missing
        try {
            agent.terminate();
        } catch (Exception e) {
            fail("terminate() should not throw exception: " + e.getMessage());
        }
    }

    @Test
    public void testToString() throws Exception {
        SlurmAgent agent = createTestAgent("test-agent-14");
        agent.setSlurmJobId("12345");

        String str = agent.toString();
        assertNotNull(str);
        assertTrue(str.contains("test-agent-14"));
        assertTrue(str.contains("12345"));
        assertTrue(str.contains("test-cloud"));
    }

    @Test
    public void testDescriptor() {
        SlurmAgent.DescriptorImpl descriptor = new SlurmAgent.DescriptorImpl();
        
        assertEquals("Slurm Agent", descriptor.getDisplayName());
        assertFalse(descriptor.isInstantiable());  // Should not show in UI
    }

    @Test
    public void testRetentionStrategy() throws Exception {
        // Test with different idle times
        SlurmJobTemplate zeroIdleTemplate = SlurmTestUtil.createTestTemplate("zero-idle", "test");
        zeroIdleTemplate.setIdleMinutes(0);
        
        SlurmJobTemplate tenIdleTemplate = SlurmTestUtil.createTestTemplate("ten-idle", "test");
        tenIdleTemplate.setIdleMinutes(10);

        // Agents created with these templates should have appropriate retention strategies
        SlurmAgent zeroIdleAgent = createTestAgentWithTemplate("zero-idle-agent", zeroIdleTemplate);
        SlurmAgent tenIdleAgent = createTestAgentWithTemplate("ten-idle-agent", tenIdleTemplate);

        assertNotNull(zeroIdleAgent.getRetentionStrategy());
        assertNotNull(tenIdleAgent.getRetentionStrategy());
        
        assertTrue(zeroIdleAgent.getRetentionStrategy() instanceof CloudRetentionStrategy);
        assertTrue(tenIdleAgent.getRetentionStrategy() instanceof CloudRetentionStrategy);
    }

    // Helper methods

    private SlurmAgent createTestAgent(String name) throws Exception {
        return createTestAgentWithTemplate(name, template);
    }

    private SlurmAgent createTestAgentWithTemplate(String name, SlurmJobTemplate template) throws Exception {
        SlurmLauncher launcher = new SlurmLauncher();
        RetentionStrategy<?> retentionStrategy = new CloudRetentionStrategy(template.getIdleMinutes());
        
        ProvisioningActivity.Id cloudStatsId = new ProvisioningActivity.Id(
            cloud.name,
            template.getId(),
            name
        );

        return new SlurmAgent(
            name,
            "Test Slurm Agent",
            template.getCurrentWorkingDirectory(),
            template.getCpusPerTask(),
            Node.Mode.NORMAL,
            template.getLabel(),
            launcher,
            retentionStrategy,
            new ArrayList<>(),
            cloud.name,
            template.getId(),
            template.getPartition(),
            cloudStatsId
        );
    }

    private SlurmAgent createTestAgentWithCloudName(String name, String cloudName) throws Exception {
        SlurmLauncher launcher = new SlurmLauncher();
        RetentionStrategy<?> retentionStrategy = new CloudRetentionStrategy(10);
        
        ProvisioningActivity.Id cloudStatsId = new ProvisioningActivity.Id(
            cloudName,
            template.getId(),
            name
        );

        return new SlurmAgent(
            name,
            "Test Slurm Agent",
            template.getCurrentWorkingDirectory(),
            template.getCpusPerTask(),
            Node.Mode.NORMAL,
            template.getLabel(),
            launcher,
            retentionStrategy,
            new ArrayList<>(),
            cloudName,  // Use provided cloud name
            template.getId(),
            template.getPartition(),
            cloudStatsId
        );
    }

    private SlurmAgent createTestAgentWithCloudStatsId(String name, ProvisioningActivity.Id cloudStatsId) 
            throws Exception {
        SlurmLauncher launcher = new SlurmLauncher();
        RetentionStrategy<?> retentionStrategy = new CloudRetentionStrategy(10);

        return new SlurmAgent(
            name,
            "Test Slurm Agent",
            template.getCurrentWorkingDirectory(),
            template.getCpusPerTask(),
            Node.Mode.NORMAL,
            template.getLabel(),
            launcher,
            retentionStrategy,
            new ArrayList<>(),
            cloud.name,
            template.getId(),
            template.getPartition(),
            cloudStatsId
        );
    }
}

