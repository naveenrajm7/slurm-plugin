package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.util.FormValidation;
import jakarta.servlet.http.HttpServletResponse;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * Tests for {@link SlurmCloud}.
 */
public class SlurmCloudTest {

    @Test
    public void testCloudDefaults() {
        SlurmCloud cloud = new SlurmCloud("test", "http://localhost:6820", null, "general", 10, 60);
        assertEquals("test", cloud.name);
        assertNotNull(cloud.getJobTemplates());
        assertEquals(0, cloud.getJobTemplates().size());
    }

    @Test
    public void testDescriptorValidation() {
        SlurmCloud.DescriptorImpl descriptor = new SlurmCloud.DescriptorImpl();

        // Valid URL
        FormValidation validation = descriptor.doCheckSlurmRestApiUrl("http://localhost:6820");
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Empty URL
        validation = descriptor.doCheckSlurmRestApiUrl("");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);

        // Invalid URL
        validation = descriptor.doCheckSlurmRestApiUrl("not-a-url");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
    }

    @Test
    public void testMaxAgentsValidation() {
        SlurmCloud.DescriptorImpl descriptor = new SlurmCloud.DescriptorImpl();

        // Valid value
        FormValidation validation = descriptor.doCheckMaxAgents("10");
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Negative value
        validation = descriptor.doCheckMaxAgents("-1");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);

        // Not a number
        validation = descriptor.doCheckMaxAgents("abc");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
    }

    /**
     * Tests for {@link SlurmCloud#doCreate} — require a live Jenkins instance for
     * permission checks, and mock the Stapler HTTP layer.
     */
    @Nested
    @WithJenkins
    class DoCreateTest {

        private SlurmCloud makeCloud() {
            return new SlurmCloud("test-cloud", "http://localhost:6820", null, "general", 10, 60);
        }

        private StaplerRequest mockRequestWithName(SlurmCloud cloud, String name) throws Exception {
            JSONObject formData = new JSONObject();
            formData.put("name", name);

            SlurmJobTemplate template = new SlurmJobTemplate();
            template.setName(name);

            StaplerRequest req = mock(StaplerRequest.class);
            when(req.getSubmittedForm()).thenReturn(formData);
            when(req.bindJSON(SlurmJobTemplate.class, formData)).thenReturn(template);
            return req;
        }

        @Test
        void doCreate_validName_addsTemplateAndRedirects(JenkinsRule j) throws Exception {
            SlurmCloud cloud = makeCloud();
            StaplerRequest req = mockRequestWithName(cloud, "gpu-template");
            StaplerResponse rsp = mock(StaplerResponse.class);

            cloud.doCreate(req, rsp);

            assertEquals(1, cloud.getJobTemplates().size());
            assertEquals("gpu-template", cloud.getJobTemplates().get(0).getName());
            verify(rsp).sendRedirect("templates");
        }

        @Test
        void doCreate_emptyName_sends400(JenkinsRule j) throws Exception {
            SlurmCloud cloud = makeCloud();
            StaplerRequest req = mockRequestWithName(cloud, "");
            StaplerResponse rsp = mock(StaplerResponse.class);

            cloud.doCreate(req, rsp);

            verify(rsp).sendError(HttpServletResponse.SC_BAD_REQUEST, "Job template name is required");
            assertEquals(0, cloud.getJobTemplates().size());
        }

        @Test
        void doCreate_blankName_sends400(JenkinsRule j) throws Exception {
            SlurmCloud cloud = makeCloud();
            StaplerRequest req = mockRequestWithName(cloud, "   ");
            StaplerResponse rsp = mock(StaplerResponse.class);

            cloud.doCreate(req, rsp);

            verify(rsp).sendError(HttpServletResponse.SC_BAD_REQUEST, "Job template name is required");
            assertEquals(0, cloud.getJobTemplates().size());
        }

        @Test
        void doCreate_duplicateName_sends409(JenkinsRule j) throws Exception {
            SlurmCloud cloud = makeCloud();

            // Add first template
            StaplerRequest req1 = mockRequestWithName(cloud, "gpu-template");
            StaplerResponse rsp1 = mock(StaplerResponse.class);
            cloud.doCreate(req1, rsp1);
            assertEquals(1, cloud.getJobTemplates().size());

            // Attempt duplicate
            StaplerRequest req2 = mockRequestWithName(cloud, "gpu-template");
            StaplerResponse rsp2 = mock(StaplerResponse.class);
            cloud.doCreate(req2, rsp2);

            verify(rsp2).sendError(HttpServletResponse.SC_CONFLICT,
                    "Template name must be unique: gpu-template");
            assertEquals(1, cloud.getJobTemplates().size());
        }

        @Test
        void doCreate_multipleDistinctNames_allAdded(JenkinsRule j) throws Exception {
            SlurmCloud cloud = makeCloud();

            cloud.doCreate(mockRequestWithName(cloud, "cpu-template"), mock(StaplerResponse.class));
            cloud.doCreate(mockRequestWithName(cloud, "gpu-template"), mock(StaplerResponse.class));
            cloud.doCreate(mockRequestWithName(cloud, "highmem-template"), mock(StaplerResponse.class));

            assertEquals(3, cloud.getJobTemplates().size());
        }
    }
}
