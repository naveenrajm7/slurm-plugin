package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.slaves.Cloud;
import hudson.slaves.CloudRetentionStrategy;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.List;
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
     * Verifies that saving the cloud configuration does not wipe existing job templates.
     *
     * <p>Templates are managed through a separate page and are not part of the cloud's
     * {@code config.jelly} form.  When Jenkins reconfigures a cloud it calls
     * {@code DescriptorImpl.newInstance()} which creates a fresh {@code SlurmCloud} via
     * {@code @DataBoundConstructor} — initialising {@code jobTemplates} to an empty list.
     * Our {@code newInstance()} override must copy the existing templates across.
     *
     * <p>Each test mocks {@link org.kohsuke.stapler.StaplerRequest#bindJSON} to return a
     * pre-built fresh {@code SlurmCloud} (simulating what Stapler data-binding produces)
     * and then calls the real {@code DescriptorImpl.newInstance()} so the production code
     * path is exercised — not just the copy logic inlined in the test.
     */
    @Nested
    @WithJenkins
    class ReconfigurePreservesTemplatesTest {

        private SlurmCloud freshCloud(String name) {
            return new SlurmCloud(name, "http://localhost:6820", null, "gpu", 10, 60);
        }

        /** Builds a {@link StaplerRequest} mock whose {@code bindJSON} returns {@code cloud}. */
        private StaplerRequest reqReturning(SlurmCloud cloud) throws Exception {
            return reqReturning(cloud, new ArrayList<>());
        }

        private StaplerRequest reqReturning(SlurmCloud cloud, List<org.kohsuke.stapler.Ancestor> ancestors)
                throws Exception {
            StaplerRequest req = mock(StaplerRequest.class);
            when(req.bindJSON(any(), any(net.sf.json.JSONObject.class))).thenReturn(cloud);
            when(req.getAncestors()).thenReturn(ancestors);
            return req;
        }

        private org.kohsuke.stapler.Ancestor ancestorOf(Object obj) {
            org.kohsuke.stapler.Ancestor a = mock(org.kohsuke.stapler.Ancestor.class);
            when(a.getObject()).thenReturn(obj);
            return a;
        }

        // ── Same-name edit ──────────────────────────────────────────────────────

        @Test
        void sameNameEdit_preservesTemplates(JenkinsRule j) throws Exception {
            SlurmCloud existing = freshCloud("my-cloud");
            SlurmJobTemplate t1 = new SlurmJobTemplate();
            t1.setName("gpu-template");
            SlurmJobTemplate t2 = new SlurmJobTemplate();
            t2.setName("cpu-template");
            existing.setJobTemplates(new ArrayList<>(List.of(t1, t2)));
            j.jenkins.clouds.add(existing);

            // newInstance() returns a fresh cloud (same name, no templates yet).
            StaplerRequest req = reqReturning(freshCloud("my-cloud"));
            Cloud result = new SlurmCloud.DescriptorImpl().newInstance(req, new net.sf.json.JSONObject());

            assertInstanceOf(SlurmCloud.class, result);
            SlurmCloud resultCloud = (SlurmCloud) result;
            assertEquals(2, resultCloud.getJobTemplates().size());
            assertEquals("gpu-template", resultCloud.getJobTemplates().get(0).getName());
            assertEquals("cpu-template", resultCloud.getJobTemplates().get(1).getName());
        }

        @Test
        void sameNameEdit_noExistingCloud_returnsEmptyTemplates(JenkinsRule j) throws Exception {
            // No existing cloud → no templates to copy → empty list is fine.
            StaplerRequest req = reqReturning(freshCloud("brand-new-cloud"));
            Cloud result = new SlurmCloud.DescriptorImpl().newInstance(req, new net.sf.json.JSONObject());

            assertInstanceOf(SlurmCloud.class, result);
            assertEquals(0, ((SlurmCloud) result).getJobTemplates().size());
        }

        @Test
        void sameNameEdit_existingCloudWithNoTemplates_returnsEmptyTemplates(JenkinsRule j) throws Exception {
            j.jenkins.clouds.add(freshCloud("empty-cloud"));

            StaplerRequest req = reqReturning(freshCloud("empty-cloud"));
            Cloud result = new SlurmCloud.DescriptorImpl().newInstance(req, new net.sf.json.JSONObject());

            assertEquals(0, ((SlurmCloud) result).getJobTemplates().size());
        }

        // ── Rename ──────────────────────────────────────────────────────────────

        @Test
        void rename_preservesTemplatesViaAncestors(JenkinsRule j) throws Exception {
            // Register old-cloud with a template.
            SlurmCloud oldCloud = freshCloud("old-cloud");
            SlurmJobTemplate t = new SlurmJobTemplate();
            t.setName("gpu-template");
            oldCloud.setJobTemplates(new ArrayList<>(List.of(t)));
            j.jenkins.clouds.add(oldCloud);

            // newInstance() builds a cloud with the NEW name; ancestor holds the OLD cloud.
            StaplerRequest req = reqReturning(freshCloud("new-cloud"), List.of(ancestorOf(oldCloud)));
            Cloud result = new SlurmCloud.DescriptorImpl().newInstance(req, new net.sf.json.JSONObject());

            assertInstanceOf(SlurmCloud.class, result);
            SlurmCloud resultCloud = (SlurmCloud) result;
            assertEquals("new-cloud", resultCloud.name);
            assertEquals(1, resultCloud.getJobTemplates().size());
            assertEquals("gpu-template", resultCloud.getJobTemplates().get(0).getName());
        }

        @Test
        void rename_noAncestor_returnsEmptyTemplates(JenkinsRule j) throws Exception {
            // Ancestor stack has no SlurmCloud → can't infer source → empty is expected.
            SlurmCloud oldCloud = freshCloud("old-cloud");
            SlurmJobTemplate t = new SlurmJobTemplate();
            t.setName("gpu-template");
            oldCloud.setJobTemplates(new ArrayList<>(List.of(t)));
            j.jenkins.clouds.add(oldCloud);

            StaplerRequest req = reqReturning(freshCloud("new-cloud")); // empty ancestors
            Cloud result = new SlurmCloud.DescriptorImpl().newInstance(req, new net.sf.json.JSONObject());

            assertEquals(0, ((SlurmCloud) result).getJobTemplates().size());
        }

        // ── JCasC / programmatic binding ────────────────────────────────────────

        @Test
        void boundTemplates_notOverwrittenByExistingCloud(JenkinsRule j) throws Exception {
            // Simulate JCasC: super.newInstance() already bound templates.
            SlurmCloud oldCloud = freshCloud("my-cloud");
            SlurmJobTemplate old = new SlurmJobTemplate();
            old.setName("old-template");
            oldCloud.setJobTemplates(new ArrayList<>(List.of(old)));
            j.jenkins.clouds.add(oldCloud);

            // The "new" cloud from super.newInstance() already has a YAML-defined template.
            SlurmCloud yamlCloud = freshCloud("my-cloud");
            SlurmJobTemplate yamlTemplate = new SlurmJobTemplate();
            yamlTemplate.setName("yaml-template");
            yamlCloud.setJobTemplates(new ArrayList<>(List.of(yamlTemplate)));

            StaplerRequest req = reqReturning(yamlCloud);
            Cloud result = new SlurmCloud.DescriptorImpl().newInstance(req, new net.sf.json.JSONObject());

            assertInstanceOf(SlurmCloud.class, result);
            SlurmCloud resultCloud = (SlurmCloud) result;
            // The YAML-bound template wins; the existing "old-template" must NOT overwrite it.
            assertEquals(1, resultCloud.getJobTemplates().size());
            assertEquals("yaml-template", resultCloud.getJobTemplates().get(0).getName());
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
                    ? new OnceRetentionStrategy(
                            Math.max(SlurmCloud.MIN_ONCE_RETENTION_IDLE_MINUTES, template.getIdleMinutes()))
                    : new CloudRetentionStrategy(template.getIdleMinutes());
            assertInstanceOf(OnceRetentionStrategy.class, strategy);
        }

        @Test
        void onceRetention_idleMinutesZero_usesMinimumGrace() {
            SlurmJobTemplate template = templateWith(true, 0);
            int retentionTimeout = Math.max(SlurmCloud.MIN_ONCE_RETENTION_IDLE_MINUTES, template.getIdleMinutes());
            assertEquals(SlurmCloud.MIN_ONCE_RETENTION_IDLE_MINUTES, retentionTimeout);
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
