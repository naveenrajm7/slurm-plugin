package io.jenkins.plugins.slurm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmPingInfo;
import io.jenkins.plugins.slurm.client.model.JobDescMsg;
import io.jenkins.plugins.slurm.client.model.JobSubmitReq;
import io.jenkins.plugins.slurm.client.model.OpenapiError;
import io.jenkins.plugins.slurm.client.model.OpenapiJobInfoResp;
import io.jenkins.plugins.slurm.client.model.OpenapiJobSubmitResponse;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Utility class for Slurm plugin tests.
 * Provides helpers for creating test clouds, templates, credentials, and mocking Slurm API responses.
 * Pattern inspired by KubernetesTestUtil.
 */
public class SlurmTestUtil {

    private static final Logger LOGGER = Logger.getLogger(SlurmTestUtil.class.getName());

    /**
     * Creates a basic Slurm cloud for testing.
     */
    public static SlurmCloud createTestCloud(String name) {
        return new SlurmCloud(
            name,
            "http://localhost:6820",
            null,  // no credentials for basic tests
            "general",
            10,
            60
        );
    }

    /**
     * Creates a Slurm cloud with credentials.
     */
    public static SlurmCloud createTestCloudWithCredentials(String name, String credentialsId) {
        return new SlurmCloud(
            name,
            "http://localhost:6820",
            credentialsId,
            "general",
            10,
            60
        );
    }

    /**
     * Creates a basic job template for testing.
     */
    public static SlurmJobTemplate createTestTemplate(String name, String label) {
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setName(name);
        template.setLabel(label);
        template.setPartition("general");
        template.setCpusPerTask(4);
        template.setMemoryPerNode(8192L);  // 8GB in MB
        template.setTimeLimit(60);  // 60 minutes
        template.setCurrentWorkingDirectory("/tmp/jenkins");
        template.setInstanceCap(5);
        template.setIdleMinutes(10);
        template.setNodeUsageMode(Node.Mode.NORMAL);
        return template;
    }

    /**
     * Creates a GPU job template for testing.
     */
    public static SlurmJobTemplate createGpuTemplate(String name, String label) {
        SlurmJobTemplate template = createTestTemplate(name, label);
        template.setPartition("gpu");
        template.setTresPerJob("gres/gpu:1");
        template.setCpusPerTask(16);
        template.setMemoryPerNode(32768L);  // 32GB in MB
        return template;
    }

    /**
     * Creates a template with Pyxis container configuration.
     */
    public static SlurmJobTemplate createContainerTemplate(String name, String label, String containerImage) {
        SlurmJobTemplate template = createTestTemplate(name, label);
        PyxisConfig pyxis = new PyxisConfig();
        pyxis.setContainerImage(containerImage);
        pyxis.setContainerMountHome(true);
        pyxis.setContainerWorkdir("/workspace");
        template.setPyxis(pyxis);
        return template;
    }

    /**
     * Creates a Secret Text credential containing a JWT token.
     */
    public static String createTestCredentials(String credentialsId, String token) throws IOException {
        StringCredentialsImpl credentials = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            credentialsId,
            "Test JWT Token",
            Secret.fromString(token)
        );

        CredentialsStore store = CredentialsProvider.lookupStores(Jenkins.get()).iterator().next();
        store.addCredentials(Domain.global(), credentials);
        
        return credentialsId;
    }

    /**
     * Creates a mock SlurmClient for testing.
     */
    public static SlurmClient createMockClient() {
        return mock(SlurmClient.class);
    }

    /**
     * Creates a mock successful job submission response.
     */
    public static OpenapiJobSubmitResponse createMockJobSubmitResponse(String jobId) {
        OpenapiJobSubmitResponse response = new OpenapiJobSubmitResponse();
        response.setJobId(Integer.parseInt(jobId));
        response.setErrors(Collections.emptyList());
        return response;
    }

    /**
     * Creates a mock failed job submission response.
     */
    public static OpenapiJobSubmitResponse createMockJobSubmitErrorResponse(String errorMessage) {
        OpenapiJobSubmitResponse response = new OpenapiJobSubmitResponse();
        OpenapiError error = new OpenapiError();
        error.setError(errorMessage);
        List<OpenapiError> errors = new ArrayList<>();
        errors.add(error);
        response.setErrors(errors);
        return response;
    }

    /**
     * Creates a mock job info response with a specific state.
     */
    public static OpenapiJobInfoResp createMockJobInfoResponse(String jobId, String state) {
        OpenapiJobInfoResp response = new OpenapiJobInfoResp();
        // The actual structure depends on the OpenAPI model
        // This is a simplified version for testing
        return response;
    }

    /**
     * Creates a mock successful ping response.
     */
    public static SlurmPingInfo createMockPingResponse() {
        return new SlurmPingInfo(
            "slurm-controller",     // hostname
            "UP",                   // pinged
            true,                   // responding
            500L,                   // latency
            "primary",              // mode
            true,                   // primary
            "23.11.0",             // version
            "test-cluster"          // cluster
        );
    }

    /**
     * Creates a mock failed ping response (controller not responding).
     */
    public static SlurmPingInfo createMockFailedPingResponse() {
        return new SlurmPingInfo(
            null,           // hostname
            "DOWN",         // pinged
            false,          // responding
            null,           // latency
            null,           // mode
            null,           // primary
            null,           // version
            null            // cluster
        );
    }

    /**
     * Sets up a mock SlurmClient with successful job submission.
     */
    public static void setupMockClientForSuccessfulSubmission(SlurmClient mockClient, String jobId) throws Exception {
        OpenapiJobSubmitResponse submitResponse = createMockJobSubmitResponse(jobId);
        when(mockClient.submitJob(any(JobSubmitReq.class))).thenReturn(submitResponse);
    }

    /**
     * Sets up a mock SlurmClient with failed job submission.
     */
    public static void setupMockClientForFailedSubmission(SlurmClient mockClient, String errorMessage) throws Exception {
        OpenapiJobSubmitResponse errorResponse = createMockJobSubmitErrorResponse(errorMessage);
        when(mockClient.submitJob(any(JobSubmitReq.class))).thenReturn(errorResponse);
    }

    /**
     * Sets up a mock SlurmClient with successful ping.
     */
    public static void setupMockClientForSuccessfulPing(SlurmClient mockClient) throws Exception {
        SlurmPingInfo pingInfo = createMockPingResponse();
        when(mockClient.getSlurmInfo()).thenReturn(pingInfo);
    }

    /**
     * Sets up a mock SlurmClient with failed ping.
     */
    public static void setupMockClientForFailedPing(SlurmClient mockClient) throws Exception {
        SlurmPingInfo pingInfo = createMockFailedPingResponse();
        when(mockClient.getSlurmInfo()).thenReturn(pingInfo);
    }

    /**
     * Verifies that an agent name matches the expected pattern.
     */
    public static void assertAgentNameFormat(String agentName, String expectedPrefix) {
        assertNotNull(agentName);
        assertTrue(String.format("Agent name '%s' should start with '%s'", agentName, expectedPrefix), 
            agentName.startsWith(expectedPrefix));
        // Agent names should follow pattern: cloudName-templateName-timestamp
        assertTrue(String.format("Agent name '%s' should match pattern 'prefix-timestamp'", agentName), 
            agentName.matches("^[a-zA-Z0-9\\-]+-[0-9]+$"));
    }

    /**
     * Waits for a condition to be true, with timeout.
     */
    public static boolean waitForCondition(java.util.concurrent.Callable<Boolean> condition, 
                                          int timeoutSeconds) {
        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < endTime) {
            try {
                if (condition.call()) {
                    return true;
                }
                Thread.sleep(500);
            } catch (Exception e) {
                LOGGER.warning("Exception while waiting for condition: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Gets the count of Slurm agents in Jenkins.
     */
    public static int getSlurmAgentCount(Jenkins jenkins, String cloudName) {
        int count = 0;
        for (Node node : jenkins.getNodes()) {
            if (node instanceof SlurmAgent) {
                SlurmAgent agent = (SlurmAgent) node;
                if (cloudName == null || cloudName.equals(agent.getCloudName())) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Removes all Slurm agents from Jenkins.
     */
    public static void removeAllSlurmAgents(Jenkins jenkins) throws IOException {
        List<Node> toRemove = new ArrayList<>();
        for (Node node : jenkins.getNodes()) {
            if (node instanceof SlurmAgent) {
                toRemove.add(node);
            }
        }
        for (Node node : toRemove) {
            jenkins.removeNode(node);
        }
    }

    /**
     * Creates a mock TaskListener for testing.
     */
    public static TaskListener createMockTaskListener() {
        return TaskListener.NULL;
    }

    /**
     * Verifies that a label matches a template.
     */
    public static boolean templateMatchesLabel(SlurmJobTemplate template, Label label) {
        String labelString = label != null ? label.getName() : null;
        return template.canTake(labelString);
    }

    /**
     * Creates a test label.
     */
    public static Label createLabel(String labelName) {
        return Label.get(labelName);
    }

    /**
     * Builder pattern for creating complex test scenarios.
     */
    public static class SlurmTestScenario {
        private String cloudName = "test-cloud";
        private String credentialsId;
        private List<SlurmJobTemplate> templates = new ArrayList<>();
        private int maxAgents = 10;
        private int agentTimeout = 60;
        
        public SlurmTestScenario withCloudName(String name) {
            this.cloudName = name;
            return this;
        }
        
        public SlurmTestScenario withCredentials(String credentialsId) {
            this.credentialsId = credentialsId;
            return this;
        }
        
        public SlurmTestScenario withTemplate(SlurmJobTemplate template) {
            this.templates.add(template);
            return this;
        }
        
        public SlurmTestScenario withMaxAgents(int maxAgents) {
            this.maxAgents = maxAgents;
            return this;
        }
        
        public SlurmTestScenario withAgentTimeout(int minutes) {
            this.agentTimeout = minutes;
            return this;
        }
        
        public SlurmCloud build() {
            SlurmCloud cloud = new SlurmCloud(
                cloudName,
                "http://localhost:6820",
                credentialsId,
                "general",
                maxAgents,
                agentTimeout
            );
            cloud.setJobTemplates(templates);
            return cloud;
        }
    }
}

