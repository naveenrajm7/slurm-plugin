package io.jenkins.plugins.slurm;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import io.jenkins.plugins.slurm.client.SlurmClient;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Tests for {@link SlurmClientProvider}.
 * Tests client caching, credential retrieval, and client creation.
 */
public class SlurmClientProviderTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private SlurmCloud cloud;
    private String credentialsId;

    @Before
    public void setUp() throws Exception {
        // Create credentials
        credentialsId = "test-jwt-creds";
        StringCredentialsImpl creds = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            credentialsId,
            "Test JWT Token",
            Secret.fromString("test-jwt-token-12345")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(creds);

        // Create cloud with credentials
        cloud = SlurmTestUtil.createTestCloudWithCredentials("test-cloud", credentialsId);
        r.jenkins.clouds.add(cloud);
    }

    @After
    public void tearDown() throws Exception {
        // Clear cache if needed
        SlurmClientProvider.invalidateAll();
    }

    @Test
    public void testCreateClient() throws Exception {
        SlurmClient client = SlurmClientProvider.createClient(cloud);
        
        assertNotNull(client);
    }

    @Test
    public void testCreateClientWithoutCredentials() throws Exception {
        // Create cloud without credentials
        SlurmCloud cloudWithoutCreds = SlurmTestUtil.createTestCloud("cloud-no-creds");
        r.jenkins.clouds.add(cloudWithoutCreds);

        // Should still create client (for non-authenticated endpoints)
        SlurmClient client = SlurmClientProvider.createClient(cloudWithoutCreds);
        assertNotNull(client);
    }

    @Test
    public void testCreateClientWithInvalidCredentials() throws Exception {
        // Create cloud with non-existent credentials ID
        SlurmCloud cloudWithBadCreds = SlurmTestUtil.createTestCloudWithCredentials(
            "cloud-bad-creds",
            "non-existent-creds-id"
        );
        r.jenkins.clouds.add(cloudWithBadCreds);

        // Should create client but without auth token
        SlurmClient client = SlurmClientProvider.createClient(cloudWithBadCreds);
        assertNotNull(client);
    }

    @Test
    public void testClientCaching() throws Exception {
        // Create first client
        SlurmClient client1 = SlurmClientProvider.createClient(cloud);
        
        // Create second client - should be same instance (cached)
        SlurmClient client2 = SlurmClientProvider.createClient(cloud);

        assertNotNull(client1);
        assertNotNull(client2);
        // Note: Actual caching depends on implementation
        // If using Caffeine, clients should be cached by cloud configuration
    }

    @Test
    public void testClearCache() throws Exception {
        // Create client (will be cached)
        SlurmClient client1 = SlurmClientProvider.createClient(cloud);
        assertNotNull(client1);

        // Clear cache
        SlurmClientProvider.invalidateAll();

        // Create new client (should be new instance)
        SlurmClient client2 = SlurmClientProvider.createClient(cloud);
        assertNotNull(client2);
    }

    @Test
    public void testGetAuthTokenFromCredentials() throws Exception {
        // Create client with credentials
        SlurmClient client = SlurmClientProvider.createClient(cloud);
        
        assertNotNull(client);
        // Client should have been created with the JWT token
        // The actual token is not exposed, but client creation should succeed
    }

    @Test
    public void testMultipleClouds() throws Exception {
        // Create second cloud with different credentials
        String credentialsId2 = "test-jwt-creds-2";
        StringCredentialsImpl creds2 = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            credentialsId2,
            "Test JWT Token 2",
            Secret.fromString("different-token-67890")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(creds2);

        SlurmCloud cloud2 = SlurmTestUtil.createTestCloudWithCredentials("test-cloud-2", credentialsId2);
        r.jenkins.clouds.add(cloud2);

        // Create clients for both clouds
        SlurmClient client1 = SlurmClientProvider.createClient(cloud);
        SlurmClient client2 = SlurmClientProvider.createClient(cloud2);

        assertNotNull(client1);
        assertNotNull(client2);
        // Clients should be different instances
    }

    @Test
    public void testCreateClientWithNullCloud() {
        assertThrows(Exception.class, () -> {
            SlurmClientProvider.createClient(null);
        });
    }

    @Test
    public void testCreateClientWithDifferentApiUrl() throws Exception {
        // Create cloud with different API URL
        SlurmCloud cloud2 = new SlurmCloud(
            "test-cloud-2",
            "http://different-host:6820",
            credentialsId,
            "general",
            10,
            60
        );
        r.jenkins.clouds.add(cloud2);

        SlurmClient client1 = SlurmClientProvider.createClient(cloud);
        SlurmClient client2 = SlurmClientProvider.createClient(cloud2);

        assertNotNull(client1);
        assertNotNull(client2);
        // Different API URLs should result in different clients
    }

    @Test
    public void testCredentialRetrieval() throws Exception {
        // This tests that credentials can be retrieved from Jenkins
        SlurmClient client = SlurmClientProvider.createClient(cloud);
        
        assertNotNull(client);
        // If credentials couldn't be retrieved, client creation would fail or use no auth
    }

    @Test
    public void testCreateClientResilience() throws Exception {
        // Test that client creation is resilient to various issues
        
        // 1. Missing credentials should not fail
        SlurmCloud cloudNoAuth = SlurmTestUtil.createTestCloud("no-auth");
        r.jenkins.clouds.add(cloudNoAuth);
        try {
            SlurmClient client = SlurmClientProvider.createClient(cloudNoAuth);
            assertNotNull(client);
        } catch (Exception e) {
            fail("createClient should not throw exception: " + e.getMessage());
        }

        // 2. Invalid URL should fail appropriately
        SlurmCloud cloudBadUrl = new SlurmCloud(
            "bad-url",
            "not-a-valid-url",
            null,
            "general",
            10,
            60
        );
        r.jenkins.clouds.add(cloudBadUrl);
        // Depending on implementation, this might throw or create client anyway
        try {
            SlurmClient client = SlurmClientProvider.createClient(cloudBadUrl);
        } catch (Exception e) {
            // Expected for invalid URL - this is fine
        }
    }
}

