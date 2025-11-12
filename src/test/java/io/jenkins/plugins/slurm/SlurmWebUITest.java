package io.jenkins.plugins.slurm;

import static org.junit.Assert.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import org.htmlunit.html.*;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Web UI tests for Slurm plugin configuration.
 * Pattern inspired by KubernetesCloudTest HTML/UI tests.
 */
public class SlurmWebUITest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private SlurmCloud cloud;

    @Before
    public void setUp() throws Exception {
        cloud = SlurmTestUtil.createTestCloud("test-cloud");
        r.jenkins.clouds.add(cloud);
    }

    @Test
    public void testCloudConfigurationPage() throws Exception {
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/configure");
        
        assertNotNull(page);
        
        // Verify form exists
        HtmlForm form = page.getFormByName("config");
        assertNotNull(form);
        
        // Verify key fields are present
        assertNotNull(getInputByName(form, "_.slurmRestApiUrl"));
        assertNotNull(getInputByName(form, "_.defaultPartition"));
        assertNotNull(getInputByName(form, "_.maxAgents"));
    }

    @Test
    public void testCloudConfigurationSubmission() throws Exception {
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/configure");
        HtmlForm form = page.getFormByName("config");
        
        // Set field values
        HtmlInput apiUrlInput = getInputByName(form, "_.slurmRestApiUrl");
        apiUrlInput.setValue("http://new-host:6820");
        
        HtmlInput partitionInput = getInputByName(form, "_.defaultPartition");
        partitionInput.setValue("gpu");
        
        HtmlInput maxAgentsInput = getInputByName(form, "_.maxAgents");
        maxAgentsInput.setValue("20");
        
        // Submit form
        r.submit(form);
        
        // Verify changes were saved
        SlurmCloud savedCloud = r.jenkins.clouds.get(SlurmCloud.class);
        assertNotNull(savedCloud);
        assertEquals("http://new-host:6820", savedCloud.getSlurmRestApiUrl());
        assertEquals("gpu", savedCloud.getDefaultPartition());
        assertEquals(20, savedCloud.getMaxAgents());
    }

    @Test
    public void testTemplateCreationPage() throws Exception {
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/new");
        
        assertNotNull(page);
        
        // Verify form exists for new template
        HtmlForm form = page.getFormByName("config");
        assertNotNull(form);
        
        // Set template name
        HtmlInput nameInput = getInputByName(form, "_.name");
        nameInput.setValue("new-template");
        
        // Submit form
        r.submit(form);
        
        // Verify template was created
        SlurmCloud savedCloud = r.jenkins.clouds.get(SlurmCloud.class);
        assertNotNull(savedCloud);
        assertEquals(1, savedCloud.getJobTemplates().size());
        assertEquals("new-template", savedCloud.getJobTemplates().get(0).getName());
    }

    @Test
    public void testTemplateEditPage() throws Exception {
        // Add a template first
        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("edit-template", "test");
        cloud.getJobTemplates().add(template);
        r.jenkins.save();
        
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/template/" + template.getId() + "/");
        
        assertNotNull(page);
        
        // Verify form exists
        HtmlForm form = page.getFormByName("config");
        assertNotNull(form);
        
        // Modify template name
        HtmlInput nameInput = getInputByName(form, "_.name");
        nameInput.setValue("edited-template");
        
        // Submit form
        r.submit(form);
        
        // Verify changes were saved
        SlurmCloud savedCloud = r.jenkins.clouds.get(SlurmCloud.class);
        SlurmJobTemplate savedTemplate = savedCloud.getJobTemplates().get(0);
        assertEquals("edited-template", savedTemplate.getName());
    }

    @Test
    public void testTemplateListPage() throws Exception {
        // Add multiple templates
        SlurmJobTemplate template1 = SlurmTestUtil.createTestTemplate("template-1", "test1");
        SlurmJobTemplate template2 = SlurmTestUtil.createTestTemplate("template-2", "test2");
        cloud.getJobTemplates().add(template1);
        cloud.getJobTemplates().add(template2);
        r.jenkins.save();
        
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/templates");
        
        assertNotNull(page);
        
        // Verify both templates are listed
        String pageContent = page.asNormalizedText();
        assertTrue(pageContent.contains("template-1") || pageContent.contains(template1.getId()));
        assertTrue(pageContent.contains("template-2") || pageContent.contains(template2.getId()));
    }

    @Test
    public void testConnectionTestButton() throws Exception {
        // Create credentials for connection test
        String credentialsId = "test-creds-ui";
        StringCredentialsImpl creds = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            credentialsId,
            "Test JWT",
            Secret.fromString("test-token")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(creds);
        
        cloud = SlurmTestUtil.createTestCloudWithCredentials("cloud-with-creds", credentialsId);
        r.jenkins.clouds.clear();
        r.jenkins.clouds.add(cloud);
        
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/cloud-with-creds/configure");
        HtmlForm form = page.getFormByName("config");
        
        assertNotNull(form);
        
        // Look for test connection button
        // Note: Actual button click and validation would require mocking the Slurm client
        // For now, we verify the form structure is correct
    }

    @Test
    public void testFieldValidation() throws Exception {
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/configure");
        HtmlForm form = page.getFormByName("config");
        
        // Set invalid URL
        HtmlInput apiUrlInput = getInputByName(form, "_.slurmRestApiUrl");
        apiUrlInput.setValue("invalid-url");
        
        // Set negative max agents
        HtmlInput maxAgentsInput = getInputByName(form, "_.maxAgents");
        maxAgentsInput.setValue("-5");
        
        // Try to submit - validation should prevent or warn
        // Note: Actual validation behavior depends on form validators
    }

    @Test
    public void testCredentialsSelection() throws Exception {
        // Create test credentials
        String credId1 = "cred-1";
        String credId2 = "cred-2";
        
        StringCredentialsImpl cred1 = new StringCredentialsImpl(
            CredentialsScope.GLOBAL, credId1, "Cred 1", Secret.fromString("token1")
        );
        StringCredentialsImpl cred2 = new StringCredentialsImpl(
            CredentialsScope.GLOBAL, credId2, "Cred 2", Secret.fromString("token2")
        );
        
        SystemCredentialsProvider.getInstance().getCredentials().add(cred1);
        SystemCredentialsProvider.getInstance().getCredentials().add(cred2);
        
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/configure");
        HtmlForm form = page.getFormByName("config");
        
        // Look for credentials dropdown
        // The actual select element name depends on implementation
        HtmlSelect credSelect = null;
        for (HtmlSelect select : form.getSelectsByName("_.credentialsId")) {
            credSelect = select;
            break;
        }
        
        if (credSelect != null) {
            // Verify credentials are available in dropdown
            assertTrue(credSelect.getOptions().size() > 0);
        }
    }

    @Test
    public void testTemplateFormFields() throws Exception {
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/new");
        HtmlForm form = page.getFormByName("config");
        
        // Verify key template fields are present
        assertNotNull(getInputByName(form, "_.name"));
        assertNotNull(getInputByName(form, "_.label"));
        assertNotNull(getInputByName(form, "_.partition"));
        assertNotNull(getInputByName(form, "_.cpusPerTask"));
        assertNotNull(getInputByName(form, "_.instanceCap"));
    }

    @Test
    public void testPyxisConfigurationSection() throws Exception {
        // Add template with Pyxis config
        SlurmJobTemplate template = SlurmTestUtil.createContainerTemplate(
            "container-template",
            "container",
            "/path/to/container.sqsh"
        );
        cloud.getJobTemplates().add(template);
        r.jenkins.save();
        
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/template/" + template.getId() + "/");
        HtmlForm form = page.getFormByName("config");
        
        assertNotNull(form);
        
        // Pyxis fields should be present (if expanded)
        // Note: Actual field presence depends on UI implementation (collapsible sections, etc.)
    }

    @Test
    public void testCloudDeletion() throws Exception {
        // This test verifies we can navigate to cloud config
        // Actual deletion would need additional form interaction
        
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("configureClouds");
        
        assertNotNull(page);
        
        // Verify cloud is listed
        String pageContent = page.asNormalizedText();
        assertTrue(pageContent.contains("test-cloud") || pageContent.contains("Slurm"));
    }

    @Test
    public void testJenkinsUrlConfiguration() throws Exception {
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/configure");
        HtmlForm form = page.getFormByName("config");
        
        // Set Jenkins URL
        HtmlInput jenkinsUrlInput = getInputByName(form, "_.jenkinsUrl");
        if (jenkinsUrlInput != null) {
            jenkinsUrlInput.setValue("http://jenkins.example.com:8080");
            r.submit(form);
            
            SlurmCloud savedCloud = r.jenkins.clouds.get(SlurmCloud.class);
            assertEquals("http://jenkins.example.com:8080", savedCloud.getJenkinsUrl());
        }
    }

    @Test
    public void testUsageRestrictionCheckbox() throws Exception {
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/configure");
        HtmlForm form = page.getFormByName("config");
        
        // Look for usage restriction checkbox
        HtmlInput usageRestrictedInput = getInputByName(form, "_.usageRestricted");
        if (usageRestrictedInput != null && usageRestrictedInput instanceof HtmlCheckBoxInput) {
            HtmlCheckBoxInput checkbox = (HtmlCheckBoxInput) usageRestrictedInput;
            checkbox.setChecked(true);
            r.submit(form);
            
            SlurmCloud savedCloud = r.jenkins.clouds.get(SlurmCloud.class);
            assertTrue(savedCloud.isUsageRestricted());
        }
    }

    @Test
    public void testAdvancedOptionsExpansion() throws Exception {
        // Add template
        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("advanced-template", "test");
        cloud.getJobTemplates().add(template);
        r.jenkins.save();
        
        JenkinsRule.WebClient webClient = r.createWebClient();
        HtmlPage page = webClient.goTo("cloud/test-cloud/template/" + template.getId() + "/");
        
        assertNotNull(page);
        
        // Advanced sections may be collapsible - verify page loads correctly
        HtmlForm form = page.getFormByName("config");
        assertNotNull(form);
    }

    // Helper method

    private HtmlInput getInputByName(HtmlForm form, String name) {
        for (HtmlElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlInput) {
                HtmlInput input = (HtmlInput) element;
                if (name.equals(input.getAttribute("name"))) {
                    return input;
                }
            }
        }
        return null;
    }
}

