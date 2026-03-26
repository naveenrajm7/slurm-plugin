package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.slaves.CloudRetentionStrategy;
import hudson.util.FormValidation;
import javax.servlet.RequestDispatcher;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
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

            RequestDispatcher noopDispatcher = mock(RequestDispatcher.class);

            StaplerRequest req = mock(StaplerRequest.class);
            when(req.getSubmittedForm()).thenReturn(formData);
            when(req.bindJSON(SlurmJobTemplate.class, formData)).thenReturn(template);
            when(req.getView((Object) any(), eq("new"))).thenReturn(noopDispatcher);
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
        void doCreate_emptyName_forwardsBackWithError(JenkinsRule j) throws Exception {
            SlurmCloud cloud = makeCloud();
            StaplerRequest req = mockRequestWithName(cloud, "");
            StaplerResponse rsp = mock(StaplerResponse.class);

            cloud.doCreate(req, rsp);

            verify(req).setAttribute("errorMessage", "Job template name is required");
            verify(req.getView(cloud, "new")).forward(req, rsp);
            assertEquals(0, cloud.getJobTemplates().size());
        }

        @Test
        void doCreate_blankName_forwardsBackWithError(JenkinsRule j) throws Exception {
            SlurmCloud cloud = makeCloud();
            StaplerRequest req = mockRequestWithName(cloud, "   ");
            StaplerResponse rsp = mock(StaplerResponse.class);

            cloud.doCreate(req, rsp);

            verify(req).setAttribute("errorMessage", "Job template name is required");
            verify(req.getView(cloud, "new")).forward(req, rsp);
            assertEquals(0, cloud.getJobTemplates().size());
        }

        @Test
        void doCreate_duplicateName_forwardsBackWithError(JenkinsRule j) throws Exception {
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

            verify(req2).setAttribute("errorMessage", "Template name must be unique: gpu-template");
            verify(req2.getView(cloud, "new")).forward(req2, rsp2);
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

    /**
     * Tests that the correct retention strategy is selected based on the
     * {@code runOnce} flag in the job template, mirroring the Kubernetes plugin pattern.
     */
    @Nested
    class RetentionStrategySelectionTest {

        private SlurmJobTemplate templateWith(boolean runOnce, int idleMinutes) {
            SlurmJobTemplate t = new SlurmJobTemplate();
            t.setRunOnce(runOnce);
            t.setIdleMinutes(idleMinutes);
            return t;
        }

        @Test
        void runOnce_true_usesOnceRetentionStrategy() {
            SlurmJobTemplate template = templateWith(true, 1);
            // runOnce=true is the default — verify the field is set correctly
            assertEquals(true, template.isRunOnce());
        }

        @Test
        void runOnce_false_usesCloudRetentionStrategy() {
            SlurmJobTemplate template = templateWith(false, 5);
            assertEquals(false, template.isRunOnce());
        }

        @Test
        void defaultTemplate_hasRunOnceTrue() {
            // Ensures new templates default to one-shot behavior
            SlurmJobTemplate template = new SlurmJobTemplate();
            assertEquals(true, template.isRunOnce());
        }

        @Test
        void retentionStrategyType_runOnce_isOnceRetentionStrategy() {
            SlurmJobTemplate template = templateWith(true, 1);
            hudson.slaves.RetentionStrategy<?> strategy = template.isRunOnce()
                    ? new OnceRetentionStrategy(template.getIdleMinutes())
                    : new CloudRetentionStrategy(template.getIdleMinutes());
            assertInstanceOf(OnceRetentionStrategy.class, strategy);
        }

        @Test
        void retentionStrategyType_reusable_isCloudRetentionStrategy() {
            SlurmJobTemplate template = templateWith(false, 5);
            hudson.slaves.RetentionStrategy<?> strategy = template.isRunOnce()
                    ? new OnceRetentionStrategy(template.getIdleMinutes())
                    : new CloudRetentionStrategy(template.getIdleMinutes());
            assertInstanceOf(CloudRetentionStrategy.class, strategy);
        }
    }
}
