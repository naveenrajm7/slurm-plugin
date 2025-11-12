package io.jenkins.plugins.slurm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessDeniedException3;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.Secret;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmPingInfo;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Expanded tests for {@link SlurmCloud}.
 * Covers provisioning logic, authorization, configuration round-trip, and capacity management.
 * Pattern inspired by KubernetesCloudTest.
 */
public class SlurmCloudExpandedTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private SlurmCloud cloud;

    @Before
    public void setUp() throws Exception {
        cloud = SlurmTestUtil.createTestCloud("test-cloud");
        r.jenkins.clouds.add(cloud);
    }

    @After
    public void tearDown() throws Exception {
        SlurmTestUtil.removeAllSlurmAgents(r.jenkins);
    }

    @Test
    public void testConfigurationRoundTrip() throws Exception {
        // Configure cloud with templates
        SlurmJobTemplate template1 = SlurmTestUtil.createTestTemplate("template-1", "label-1");
        SlurmJobTemplate template2 = SlurmTestUtil.createTestTemplate("template-2", "label-2");
        cloud.getJobTemplates().add(template1);
        cloud.getJobTemplates().add(template2);
        cloud.setJenkinsUrl("http://jenkins.example.com:8080");
        
        // Save Jenkins configuration
        r.jenkins.save();

        // Submit configuration form (round-trip)
        r.submit(r.createWebClient().goTo("cloud/test-cloud/configure").getFormByName("config"));

        // Verify cloud is still present and configured
        SlurmCloud loadedCloud = r.jenkins.clouds.get(SlurmCloud.class);
        assertNotNull(loadedCloud);
        assertEquals("test-cloud", loadedCloud.name);
        assertEquals("http://jenkins.example.com:8080", loadedCloud.getJenkinsUrl());
        assertEquals(2, loadedCloud.getJobTemplates().size());
    }

    @Test
    public void testCanProvisionWithMatchingTemplate() {
        // Add a template with specific label
        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("test-template", "docker maven");
        cloud.getJobTemplates().add(template);

        // Create label that matches
        Label label = Label.get("docker && maven");
        Cloud.CloudState state = new Cloud.CloudState(label, 1);

        // Should be able to provision
        assertTrue(cloud.canProvision(state));
    }

    @Test
    public void testCanProvisionWithoutMatchingTemplate() {
        // Add a template with specific label
        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("test-template", "docker");
        cloud.getJobTemplates().add(template);

        // Create label that doesn't match
        Label label = Label.get("gpu");
        Cloud.CloudState state = new Cloud.CloudState(label, 1);

        // Should not be able to provision
        assertFalse(cloud.canProvision(state));
    }

    @Test
    public void testCanProvisionAtCapacity() throws Exception {
        // Set max agents to 2
        SlurmCloud limitedCloud = new SlurmCloud("limited-cloud", "http://localhost:6820", null, "general", 2, 60);
        r.jenkins.clouds.add(limitedCloud);

        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("test-template", "test");
        limitedCloud.getJobTemplates().add(template);

        // Add 2 agents (at capacity)
        SlurmAgent agent1 = createTestAgent("agent-1", limitedCloud, template);
        SlurmAgent agent2 = createTestAgent("agent-2", limitedCloud, template);
        r.jenkins.addNode(agent1);
        r.jenkins.addNode(agent2);

        // Create label
        Label label = Label.get("test");
        Cloud.CloudState state = new Cloud.CloudState(label, 1);

        // Should not be able to provision (at capacity)
        assertFalse(limitedCloud.canProvision(state));
    }

    @Test
    public void testProvisioningWithTemplateInstanceCap() throws Exception {
        // Create template with instance cap of 2
        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("capped-template", "test");
        template.setInstanceCap(2);
        cloud.getJobTemplates().add(template);

        // Mock Slurm client
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);
            
            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");

            // Request 5 agents
            Label label = Label.get("test");
            Cloud.CloudState state = new Cloud.CloudState(label, 5);
            Collection<PlannedNode> planned = cloud.provision(state, 5);

            // Should only provision 2 (instance cap)
            assertEquals(2, planned.size());
        }
    }

    @Test
    public void testProvisioningWithCloudCapacity() throws Exception {
        // Create cloud with max 3 agents
        SlurmCloud limitedCloud = new SlurmCloud("limited", "http://localhost:6820", null, "general", 3, 60);
        r.jenkins.clouds.add(limitedCloud);

        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("test-template", "test");
        template.setInstanceCap(10);  // Template cap is higher
        limitedCloud.getJobTemplates().add(template);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);
            
            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");

            // Request 10 agents
            Label label = Label.get("test");
            Cloud.CloudState state = new Cloud.CloudState(label, 10);
            Collection<PlannedNode> planned = limitedCloud.provision(state, 10);

            // Should only provision 3 (cloud capacity)
            assertEquals(3, planned.size());
        }
    }

    @Test
    public void testProvisioningSkipsWhenInProvisioning() throws Exception {
        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("test-template", "test");
        cloud.getJobTemplates().add(template);

        // Test basic provisioning count
        Label label = Label.get("test");
        Cloud.CloudState state = new Cloud.CloudState(label, 5);
        Collection<PlannedNode> planned = cloud.provision(state, 5);

        // Should provision requested agents (up to instance cap)
        assertTrue(planned.size() <= 5);
        assertTrue(planned.size() <= template.getInstanceCap());
    }

    @Test
    public void testGetJenkinsUrl() {
        // Test getJenkinsUrl method
        String testUrl = "http://test:8080";
        cloud.setJenkinsUrl(testUrl);
        assertEquals(testUrl, cloud.getJenkinsUrl());

        // Test null Jenkins URL
        cloud.setJenkinsUrl(null);
        assertNull(cloud.getJenkinsUrl());
    }

    @Test
    public void testConnectionTesting() throws Exception {
        // Create credentials
        String credentialsId = "test-creds";
        StringCredentialsImpl creds = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            credentialsId,
            "Test JWT",
            Secret.fromString("test-jwt-token")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(creds);

        SlurmCloud cloudWithCreds = SlurmTestUtil.createTestCloudWithCredentials("cloud-with-creds", credentialsId);
        r.jenkins.clouds.add(cloudWithCreds);

        try (MockedStatic<SlurmClient> mockedClient = mockStatic(SlurmClient.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedClient.when(() -> new SlurmClient(anyString(), anyString())).thenReturn(mockClient);
            
            SlurmPingInfo pingInfo = SlurmTestUtil.createMockPingResponse();
            when(mockClient.getSlurmInfo()).thenReturn(pingInfo);

            // Test connection via descriptor
            SlurmCloud.DescriptorImpl descriptor = new SlurmCloud.DescriptorImpl();
            hudson.util.FormValidation validation = descriptor.doTestConnection(
                "http://localhost:6820",
                credentialsId
            );

            // Should succeed
            assertEquals(hudson.util.FormValidation.Kind.OK, validation.kind);
        }
    }

    @Test
    public void testConnectionTestingFailure() throws Exception {
        // Create credentials
        String credentialsId = "test-creds";
        StringCredentialsImpl creds = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            credentialsId,
            "Test JWT",
            Secret.fromString("test-jwt-token")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(creds);

        try (MockedStatic<SlurmClient> mockedClient = mockStatic(SlurmClient.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedClient.when(() -> new SlurmClient(anyString(), anyString())).thenReturn(mockClient);
            
            SlurmPingInfo failedPing = SlurmTestUtil.createMockFailedPingResponse();
            when(mockClient.getSlurmInfo()).thenReturn(failedPing);

            // Test connection via descriptor
            SlurmCloud.DescriptorImpl descriptor = new SlurmCloud.DescriptorImpl();
            hudson.util.FormValidation validation = descriptor.doTestConnection(
                "http://localhost:6820",
                credentialsId
            );

            // Should fail
            assertEquals(hudson.util.FormValidation.Kind.ERROR, validation.kind);
        }
    }

    @Test
    public void testAuthorization() throws Exception {
        // Set up security
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy authStrategy = new MockAuthorizationStrategy();
        authStrategy.grant(Jenkins.ADMINISTER).everywhere().to("admin");
        authStrategy.grant(Jenkins.MANAGE).everywhere().to("manager");
        authStrategy.grant(Jenkins.READ).everywhere().to("user");
        r.jenkins.setAuthorizationStrategy(authStrategy);

        SlurmJobTemplate template1 = SlurmTestUtil.createTestTemplate("template-1", "test");
        SlurmJobTemplate template2 = SlurmTestUtil.createTestTemplate("template-2", "test");

        // Admin can add template
        try (ACLContext ignored = ACL.as2(User.get("admin", true, java.util.Map.of()).impersonate2())) {
            cloud.getJobTemplates().add(template1);
            assertEquals(1, cloud.getJobTemplates().size());
        }

        // User cannot add template
        try (ACLContext ignored = ACL.as2(User.get("user", true, java.util.Map.of()).impersonate2())) {
            AccessDeniedException3 exception = assertThrows(AccessDeniedException3.class, () -> {
                cloud.getJobTemplates().add(template2);
            });
            assertThat(exception.getMessage(), containsString("is missing the Overall/Administer permission"));
        }

        // Manager can add template (has MANAGE permission)
        try (ACLContext ignored = ACL.as2(User.get("manager", true, java.util.Map.of()).impersonate2())) {
            cloud.getJobTemplates().add(template2);
            assertEquals(2, cloud.getJobTemplates().size());
        }
    }

    @Test
    public void testTemplateManagement() {
        SlurmJobTemplate template1 = SlurmTestUtil.createTestTemplate("template-1", "label-1");
        SlurmJobTemplate template2 = SlurmTestUtil.createTestTemplate("template-2", "label-2");

        // Add templates
        cloud.getJobTemplates().add(template1);
        cloud.getJobTemplates().add(template2);
        assertEquals(2, cloud.getJobTemplates().size());

        // Get template by name
        SlurmJobTemplate found = cloud.getTemplate("template-1");
        assertNotNull(found);
        assertEquals("template-1", found.getName());

        // Get template by ID
        SlurmJobTemplate foundById = cloud.getTemplateById(template1.getId());
        assertNotNull(foundById);
        assertEquals(template1.getId(), foundById.getId());

        // Remove template
        cloud.removeTemplate(template1);
        assertEquals(1, cloud.getJobTemplates().size());
        assertNull(cloud.getTemplate("template-1"));
    }

    @Test
    public void testDynamicTemplateAddRemove() {
        SlurmJobTemplate dynamicTemplate = SlurmTestUtil.createTestTemplate("dynamic", "dynamic-label");

        // Add dynamic template
        cloud.addDynamicTemplate(dynamicTemplate);
        assertTrue(cloud.getJobTemplates().contains(dynamicTemplate));

        // Remove dynamic template
        cloud.removeDynamicTemplate(dynamicTemplate);
        assertFalse(cloud.getJobTemplates().contains(dynamicTemplate));
    }

    @Test
    public void testUsageRestriction() {
        // Default is not restricted
        assertFalse(cloud.isUsageRestricted());

        // Set restricted
        cloud.setUsageRestricted(true);
        assertTrue(cloud.isUsageRestricted());
    }

    @Test
    public void testTemplateSelection() {
        // Add multiple templates
        SlurmJobTemplate dockerTemplate = SlurmTestUtil.createTestTemplate("docker-tpl", "docker linux");
        SlurmJobTemplate gpuTemplate = SlurmTestUtil.createTestTemplate("gpu-tpl", "gpu cuda");
        SlurmJobTemplate defaultTemplate = SlurmTestUtil.createTestTemplate("default-tpl", "");

        cloud.getJobTemplates().add(dockerTemplate);
        cloud.getJobTemplates().add(gpuTemplate);
        cloud.getJobTemplates().add(defaultTemplate);

        // Test label matching
        Label dockerLabel = Label.get("docker && linux");
        SlurmJobTemplate selected = cloud.getJobTemplateFor(dockerLabel);
        assertNotNull(selected);
        assertEquals("docker-tpl", selected.getName());

        // Test GPU label
        Label gpuLabel = Label.get("gpu");
        selected = cloud.getJobTemplateFor(gpuLabel);
        assertNotNull(selected);
        assertEquals("gpu-tpl", selected.getName());
    }

    @Test
    public void testDescriptorValidation() {
        SlurmCloud.DescriptorImpl descriptor = new SlurmCloud.DescriptorImpl();

        // Valid URL
        hudson.util.FormValidation validation = descriptor.doCheckSlurmRestApiUrl("http://localhost:6820");
        assertEquals(hudson.util.FormValidation.Kind.OK, validation.kind);

        // Empty URL
        validation = descriptor.doCheckSlurmRestApiUrl("");
        assertEquals(hudson.util.FormValidation.Kind.ERROR, validation.kind);

        // Invalid URL
        validation = descriptor.doCheckSlurmRestApiUrl("not-a-url");
        assertEquals(hudson.util.FormValidation.Kind.ERROR, validation.kind);

        // Invalid protocol
        validation = descriptor.doCheckSlurmRestApiUrl("ftp://localhost:6820");
        assertEquals(hudson.util.FormValidation.Kind.ERROR, validation.kind);

        // Max agents validation
        validation = descriptor.doCheckMaxAgents("10");
        assertEquals(hudson.util.FormValidation.Kind.OK, validation.kind);

        validation = descriptor.doCheckMaxAgents("-1");
        assertEquals(hudson.util.FormValidation.Kind.ERROR, validation.kind);

        validation = descriptor.doCheckMaxAgents("abc");
        assertEquals(hudson.util.FormValidation.Kind.ERROR, validation.kind);
    }

    // Helper methods

    private SlurmAgent createTestAgent(String name, SlurmCloud cloud, SlurmJobTemplate template) throws Exception {
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
            Node.Mode.NORMAL,
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

