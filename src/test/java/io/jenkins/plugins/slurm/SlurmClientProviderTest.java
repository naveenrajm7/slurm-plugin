package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import io.jenkins.plugins.slurm.client.SlurmClient;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for {@link SlurmClientProvider} client caching and, in particular, that a renewed JWT
 * credential (edited in place, keeping the same credential ID) invalidates the cached client
 * rather than serving a stale token.
 */
@WithJenkins
class SlurmClientProviderTest {

    private static final String CRED_ID = "slurm-jwt";
    private static final String CLOUD_NAME = "test-cloud";

    @BeforeEach
    void clearCache() {
        // The client cache is static; drop any entries left by other tests.
        SlurmClientProvider.invalidateAll();
    }

    private SlurmCloud cloud() {
        return new SlurmCloud(CLOUD_NAME, "http://localhost:6820", CRED_ID, "compute", 10, 60);
    }

    /** Creates or replaces the {@code slurm-jwt} secret-text credential with the given token value. */
    private void setToken(String token) throws Exception {
        SystemCredentialsProvider store = SystemCredentialsProvider.getInstance();
        store.getCredentials().removeIf(c -> c instanceof StringCredentialsImpl sc && CRED_ID.equals(sc.getId()));
        store.getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, CRED_ID, "JWT", Secret.fromString(token)));
        store.save();
    }

    @Test
    void sameToken_returnsCachedInstance(JenkinsRule j) throws Exception {
        setToken("token-A");
        SlurmCloud cloud = cloud();

        SlurmClient first = SlurmClientProvider.createClient(cloud);
        SlurmClient second = SlurmClientProvider.createClient(cloud);

        assertNotNull(first);
        assertSame(first, second, "Client should be cached when nothing changed");
    }

    @Test
    void tokenRenewed_returnsFreshInstance(JenkinsRule j) throws Exception {
        setToken("token-A");
        SlurmCloud cloud = cloud();
        SlurmClient beforeRenew = SlurmClientProvider.createClient(cloud);

        // Renew the JWT in place (same credential ID) — as a user would after expiry.
        setToken("token-B");
        SlurmClient afterRenew = SlurmClientProvider.createClient(cloud);

        assertNotNull(afterRenew);
        assertNotSame(beforeRenew, afterRenew, "A renewed token must rebuild the cached client");
    }

    @Test
    void getValidity_changesWhenTokenChanges(JenkinsRule j) throws Exception {
        SlurmCloud cloud = cloud();

        setToken("token-A");
        int validityA = SlurmClientProvider.getValidity(cloud);

        setToken("token-B");
        int validityB = SlurmClientProvider.getValidity(cloud);

        assertNotEquals(validityA, validityB, "Validity hash must reflect the current token secret");
    }

    @Test
    void getValidity_stableForUnchangedToken(JenkinsRule j) throws Exception {
        SlurmCloud cloud = cloud();
        setToken("token-A");

        assertNotEquals(0, SlurmClientProvider.getValidity(cloud));
        int first = SlurmClientProvider.getValidity(cloud);
        int second = SlurmClientProvider.getValidity(cloud);
        // Same config + same token → identical hash (no needless invalidation).
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
    }
}
