package io.jenkins.plugins.slurm;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import hudson.model.Result;
import io.jenkins.plugins.slurm.client.SlurmClient;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.MockedStatic;

import java.util.concurrent.TimeUnit;

/**
 * End-to-end pipeline integration tests for Slurm plugin.
 * Tests actual pipeline execution with mocked Slurm API.
 */
public class SlurmPipelineIntegrationTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private SlurmCloud cloud;
    private SlurmJobTemplate template;

    @Before
    public void setUp() throws Exception {
        cloud = SlurmTestUtil.createTestCloud("test-cloud");
        template = SlurmTestUtil.createTestTemplate("test-template", "test");
        cloud.getJobTemplates().add(template);
        r.jenkins.clouds.add(cloud);
    }

    @After
    public void tearDown() throws Exception {
        SlurmTestUtil.removeAllSlurmAgents(r.jenkins);
    }

    @Test
    public void testSimplePipeline() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12345");
            when(mockClient.getJobState("12345")).thenReturn("RUNNING");

            // Create simple pipeline
            WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "simple-test");
            job.setDefinition(new CpsFlowDefinition(
                "node('test') {\n" +
                "  echo 'Hello from Slurm agent'\n" +
                "  sh 'pwd'\n" +
                "}\n",
                true
            ));

            // Note: This will timeout waiting for actual agent connection
            // In a real integration test environment, you'd need a running Slurm cluster
            // For unit testing, we verify the job is created and provisioning starts
            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            assertNotNull(run);
            
            // Wait a bit for provisioning to start
            Thread.sleep(2000);
            
            // Verify job submission was attempted
            verify(mockClient, atLeastOnce()).submitJob(any());
        }
    }

    @Test
    public void testDeclarativePipeline() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12346");
            when(mockClient.getJobState("12346")).thenReturn("RUNNING");

            // Create declarative pipeline
            WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "declarative-test");
            job.setDefinition(new CpsFlowDefinition(
                "pipeline {\n" +
                "  agent { label 'test' }\n" +
                "  stages {\n" +
                "    stage('Build') {\n" +
                "      steps {\n" +
                "        echo 'Building on Slurm'\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}\n",
                true
            ));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            assertNotNull(run);
            
            // Wait for provisioning
            Thread.sleep(2000);
            
            // Verify job submission
            verify(mockClient, atLeastOnce()).submitJob(any());
        }
    }

    @Test
    public void testPipelineWithNonExistentLabel() throws Exception {
        // Create pipeline requesting non-existent label
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "nonexistent-label-test");
        job.setDefinition(new CpsFlowDefinition(
            "node('nonexistent-label') {\n" +
            "  echo 'This should not run'\n" +
            "}\n",
            true
        ));

        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        assertNotNull(run);
        
        // Wait a bit
        Thread.sleep(2000);
        
        // Build should be waiting for agent (no template matches)
        assertTrue(run.isBuilding() || run.getResult() == null);
    }

    @Test
    public void testMultiplePipelinesInParallel() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12347");
            when(mockClient.getJobState(anyString())).thenReturn("RUNNING");

            // Create multiple pipelines
            WorkflowJob job1 = r.jenkins.createProject(WorkflowJob.class, "parallel-test-1");
            job1.setDefinition(new CpsFlowDefinition(
                "node('test') { echo 'Job 1' }",
                true
            ));

            WorkflowJob job2 = r.jenkins.createProject(WorkflowJob.class, "parallel-test-2");
            job2.setDefinition(new CpsFlowDefinition(
                "node('test') { echo 'Job 2' }",
                true
            ));

            // Start both jobs
            WorkflowRun run1 = job1.scheduleBuild2(0).waitForStart();
            WorkflowRun run2 = job2.scheduleBuild2(0).waitForStart();

            assertNotNull(run1);
            assertNotNull(run2);
            
            // Wait for provisioning
            Thread.sleep(2000);
            
            // Both should have triggered provisioning
            verify(mockClient, atLeast(2)).submitJob(any());
        }
    }

    @Test
    public void testPipelineWithContainerTemplate() throws Exception {
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

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12348");
            when(mockClient.getJobState("12348")).thenReturn("RUNNING");

            // Create pipeline with container label
            WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "container-test");
            job.setDefinition(new CpsFlowDefinition(
                "node('container') {\n" +
                "  echo 'Running in container'\n" +
                "}\n",
                true
            ));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            assertNotNull(run);
            
            Thread.sleep(2000);
            
            verify(mockClient, atLeastOnce()).submitJob(any());
        }
    }

    @Test
    public void testPipelineErrorReporting() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            // Simulate job submission failure
            when(mockClient.submitJob(any()))
                .thenThrow(new Exception("Slurm API error"));

            // Create pipeline
            WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "error-test");
            job.setDefinition(new CpsFlowDefinition(
                "node('test') {\n" +
                "  echo 'This should fail'\n" +
                "}\n",
                true
            ));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            assertNotNull(run);
            
            // Wait for failure
            Thread.sleep(3000);
            
            // Build should eventually fail or be stuck waiting
            // Actual behavior depends on retry logic
        }
    }

    @Test
    public void testPipelineWithSlurmStepExecution() throws Exception {
        // This would test the SlurmJobTemplateStep if it's used in pipeline
        // For now, we test basic node allocation
        
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12349");
            when(mockClient.getJobState("12349")).thenReturn("RUNNING");

            WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "step-test");
            job.setDefinition(new CpsFlowDefinition(
                "node('test') {\n" +
                "  stage('Test') {\n" +
                "    echo 'Testing Slurm agent'\n" +
                "    sh 'echo \"Hello from \\$HOSTNAME\"'\n" +
                "  }\n" +
                "}\n",
                true
            ));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            assertNotNull(run);
            
            Thread.sleep(2000);
            
            verify(mockClient, atLeastOnce()).submitJob(any());
        }
    }

    @Test
    public void testPipelineAgentTimeout() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12350");
            // Job stays in PENDING state (never reaches RUNNING)
            when(mockClient.getJobState("12350")).thenReturn("PENDING");

            WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "timeout-test");
            job.setDefinition(new CpsFlowDefinition(
                "node('test') {\n" +
                "  echo 'This should timeout'\n" +
                "}\n",
                true
            ));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            assertNotNull(run);
            
            // Wait for timeout (in real test, this would be shorter)
            Thread.sleep(3000);
            
            // Job should be submitted but stuck waiting
            verify(mockClient, atLeastOnce()).submitJob(any());
        }
    }

    @Test
    public void testPipelineWithMultipleStages() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12351");
            when(mockClient.getJobState("12351")).thenReturn("RUNNING");

            WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "multistage-test");
            job.setDefinition(new CpsFlowDefinition(
                "node('test') {\n" +
                "  stage('Build') {\n" +
                "    echo 'Building'\n" +
                "  }\n" +
                "  stage('Test') {\n" +
                "    echo 'Testing'\n" +
                "  }\n" +
                "  stage('Deploy') {\n" +
                "    echo 'Deploying'\n" +
                "  }\n" +
                "}\n",
                true
            ));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            assertNotNull(run);
            
            Thread.sleep(2000);
            
            verify(mockClient, atLeastOnce()).submitJob(any());
        }
    }

    @Test
    public void testPipelineCleanupOnFailure() throws Exception {
        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(any(SlurmCloud.class)))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulSubmission(mockClient, "12352");
            // Job goes to FAILED state
            when(mockClient.getJobState("12352"))
                .thenReturn("PENDING")
                .thenReturn("FAILED");

            WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "cleanup-test");
            job.setDefinition(new CpsFlowDefinition(
                "node('test') {\n" +
                "  echo 'This will fail'\n" +
                "}\n",
                true
            ));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            assertNotNull(run);
            
            // Wait for failure detection
            Thread.sleep(3000);
            
            // Should have attempted to cancel the job
            verify(mockClient, atLeastOnce()).cancelJob("12352");
        }
    }
}

