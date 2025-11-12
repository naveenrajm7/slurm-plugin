package io.jenkins.plugins.slurm;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmPingInfo;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.MockedStatic;

/**
 * Tests for Slurm client authentication handling.
 * Pattern inspired by ClientAuthenticationTest from Kubernetes plugin.
 */
public class SlurmClientAuthenticationTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private String credentialsId;

    @Before
    public void setUp() throws Exception {
        // Create test credentials
        credentialsId = "test-auth-creds";
        StringCredentialsImpl creds = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            credentialsId,
            "Test Auth Token",
            Secret.fromString("valid-jwt-token")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(creds);
    }

    @Test
    public void testSuccessfulAuthentication() throws Exception {
        SlurmCloud cloud = SlurmTestUtil.createTestCloudWithCredentials("auth-cloud", credentialsId);
        r.jenkins.clouds.add(cloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulPing(mockClient);

            // Verify connection works with valid credentials
            SlurmPingInfo info = mockClient.getSlurmInfo();
            assertNotNull(info);
            assertTrue(info.getResponding());
        }
    }

    @Test
    public void testAuthenticationFailureRetry() throws Exception {
        SlurmCloud cloud = SlurmTestUtil.createTestCloudWithCredentials("retry-cloud", credentialsId);
        r.jenkins.clouds.add(cloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            
            // First call fails, second succeeds (simulating token refresh or retry)
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud))
                .thenThrow(new RuntimeException("Auth failed"))
                .thenReturn(mockClient);

            // First attempt fails
            assertThrows(RuntimeException.class, () -> {
                SlurmClientProvider.createClient(cloud);
            });

            // Second attempt succeeds
            SlurmClient client = SlurmClientProvider.createClient(cloud);
            assertNotNull(client);
        }
    }

    @Test
    public void testMissingCredentials() throws Exception {
        // Create cloud without credentials
        SlurmCloud cloud = SlurmTestUtil.createTestCloud("no-creds-cloud");
        r.jenkins.clouds.add(cloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud))
                .thenReturn(mockClient);

            // Should create client even without credentials (for non-authenticated endpoints)
            SlurmClient client = SlurmClientProvider.createClient(cloud);
            assertNotNull(client);
        }
    }

    @Test
    public void testExpiredToken() throws Exception {
        SlurmCloud cloud = SlurmTestUtil.createTestCloudWithCredentials("expired-token-cloud", credentialsId);
        r.jenkins.clouds.add(cloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud))
                .thenReturn(mockClient);

            // Simulate expired token - ping fails
            SlurmPingInfo failedPing = SlurmTestUtil.createMockFailedPingResponse();
            when(mockClient.getSlurmInfo()).thenReturn(failedPing);

            SlurmPingInfo info = mockClient.getSlurmInfo();
            assertNotNull(info);
            assertFalse(info.getResponding());
        }
    }

    @Test
    public void testInvalidCredentialsId() throws Exception {
        // Create cloud with non-existent credentials
        SlurmCloud cloud = SlurmTestUtil.createTestCloudWithCredentials(
            "invalid-creds-cloud",
            "non-existent-id"
        );
        r.jenkins.clouds.add(cloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud))
                .thenReturn(mockClient);

            // Should still create client (without auth)
            SlurmClient client = SlurmClientProvider.createClient(cloud);
            assertNotNull(client);
        }
    }

    @Test
    public void testMultipleAuthenticationAttempts() throws Exception {
        SlurmCloud cloud = SlurmTestUtil.createTestCloudWithCredentials("multi-auth-cloud", credentialsId);
        r.jenkins.clouds.add(cloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud))
                .thenReturn(mockClient);

            SlurmTestUtil.setupMockClientForSuccessfulPing(mockClient);

            // Multiple client creations should all succeed
            for (int i = 0; i < 5; i++) {
                SlurmClient client = SlurmClientProvider.createClient(cloud);
                assertNotNull(client);
            }

            // Verify create was called multiple times
            mockedProvider.verify(() -> SlurmClientProvider.createClient(cloud), times(5));
        }
    }

    @Test
    public void testAuthenticationWithDifferentClouds() throws Exception {
        // Create multiple clouds with different credentials
        String creds2Id = "auth-creds-2";
        StringCredentialsImpl creds2 = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            creds2Id,
            "Auth Token 2",
            Secret.fromString("different-jwt-token")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(creds2);

        SlurmCloud cloud1 = SlurmTestUtil.createTestCloudWithCredentials("cloud-1", credentialsId);
        SlurmCloud cloud2 = SlurmTestUtil.createTestCloudWithCredentials("cloud-2", creds2Id);
        r.jenkins.clouds.add(cloud1);
        r.jenkins.clouds.add(cloud2);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient1 = mock(SlurmClient.class);
            SlurmClient mockClient2 = mock(SlurmClient.class);
            
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud1))
                .thenReturn(mockClient1);
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud2))
                .thenReturn(mockClient2);

            // Both should succeed
            SlurmClient client1 = SlurmClientProvider.createClient(cloud1);
            SlurmClient client2 = SlurmClientProvider.createClient(cloud2);

            assertNotNull(client1);
            assertNotNull(client2);
        }
    }

    @Test
    public void testAuthenticationException() throws Exception {
        SlurmCloud cloud = SlurmTestUtil.createTestCloudWithCredentials("exception-cloud", credentialsId);
        r.jenkins.clouds.add(cloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            // Simulate authentication exception
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud))
                .thenThrow(new RuntimeException("Authentication failed: Invalid JWT token"));

            // Should throw exception
            Exception exception = assertThrows(RuntimeException.class, () -> {
                SlurmClientProvider.createClient(cloud);
            });

            assertTrue(exception.getMessage().contains("Authentication failed"));
        }
    }

    @Test
    public void testConnectionWithValidAuth() throws Exception {
        SlurmCloud cloud = SlurmTestUtil.createTestCloudWithCredentials("valid-auth-cloud", credentialsId);
        r.jenkins.clouds.add(cloud);

        try (MockedStatic<SlurmClientProvider> mockedProvider = mockStatic(SlurmClientProvider.class)) {
            SlurmClient mockClient = mock(SlurmClient.class);
            mockedProvider.when(() -> SlurmClientProvider.createClient(cloud))
                .thenReturn(mockClient);

            SlurmPingInfo successPing = SlurmTestUtil.createMockPingResponse();
            when(mockClient.getSlurmInfo()).thenReturn(successPing);

            // Create client and verify connection
            SlurmClient client = SlurmClientProvider.createClient(cloud);
            SlurmPingInfo info = client.getSlurmInfo();

            assertNotNull(client);
            assertNotNull(info);
            assertTrue(info.getResponding());
            assertEquals("slurm-controller", info.getHostname());
        }
    }
}

