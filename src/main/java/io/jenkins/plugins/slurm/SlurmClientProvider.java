package io.jenkins.plugins.slurm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import io.jenkins.plugins.slurm.client.SlurmClient;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Slurm client creation and lifecycle per cloud.
 * 
 * Similar to Kubernetes plugin's KubernetesClientProvider, this class caches
 * Slurm clients and invalidates them when cloud configuration changes.
 */
public class SlurmClientProvider {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmClientProvider.class.getName());
    
    /**
     * Client expiration in seconds.
     * Expire clients after 30 minutes to refresh JWT tokens.
     */
    private static final long CACHE_EXPIRATION = Long.getLong(
            SlurmClientProvider.class.getPackage().getName() + ".clients.cacheExpiration",
            TimeUnit.MINUTES.toSeconds(30));
    
    private static final Cache<String, Client> clients = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_EXPIRATION, TimeUnit.SECONDS)
            .removalListener((key, value, cause) -> {
                Client client = (Client) value;
                if (client != null) {
                    LOGGER.log(Level.FINE, () -> "Expiring Slurm client " + key + ": " + cause);
                }
            })
            .build();
    
    private SlurmClientProvider() {}
    
    /**
     * Creates or retrieves a cached Slurm client for the given cloud.
     * 
     * @param cloud The Slurm cloud instance
     * @return Slurm client for the cloud
     * @throws Exception if client creation fails
     */
    static SlurmClient createClient(SlurmCloud cloud) throws Exception {
        String displayName = cloud.getDisplayName();
        int currentValidity = getValidity(cloud);
        final Client c = clients.getIfPresent(displayName);

        // Rebuild when there is no cached client, or when the cached client was built from a
        // now-stale configuration (e.g. the JWT credential was renewed in place). The stored
        // validity is compared on every read because a credential-only edit does not trigger
        // SaveableListenerImpl (that listener only fires on Jenkins global-config saves).
        if (c == null || c.getValidity() != currentValidity) {
            String authToken = getAuthToken(cloud);
            SlurmClient client = new SlurmClient(cloud.getSlurmRestApiUrl(), authToken);

            clients.put(displayName, new Client(currentValidity, client));
            LOGGER.log(
                    Level.FINE,
                    () -> (c == null ? "Created new Slurm client: " : "Refreshed stale Slurm client: ") + displayName);
            return client;
        }

        return c.getClient();
    }
    
    /**
     * Retrieves authentication token from Jenkins credentials.
     * 
     * @param cloud The Slurm cloud instance
     * @return JWT token string
     */
    private static String getAuthToken(SlurmCloud cloud) {
        String credentialsId = cloud.getCredentialsId();
        if (credentialsId == null || credentialsId.trim().isEmpty()) {
            LOGGER.warning("No credentials configured for Slurm cloud: " + cloud.getDisplayName());
            return null;
        }
        
        try {
            java.util.List<org.jenkinsci.plugins.plaincredentials.StringCredentials> credentials = 
                com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
                    org.jenkinsci.plugins.plaincredentials.StringCredentials.class,
                    (hudson.model.Item) null,
                    hudson.security.ACL.SYSTEM,
                    java.util.Collections.<com.cloudbees.plugins.credentials.domains.DomainRequirement>emptyList()
                );
            
            for (org.jenkinsci.plugins.plaincredentials.StringCredentials credential : credentials) {
                if (credentialsId.equals(credential.getId())) {
                    hudson.util.Secret secret = credential.getSecret();
                    String token = hudson.util.Secret.toString(secret);
                    LOGGER.fine("Retrieved JWT token from credentials: " + credentialsId);
                    return token;
                }
            }
            
            LOGGER.warning("Could not find credentials with ID: " + credentialsId);
            return null;
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve credentials", e);
            return null;
        }
    }
    
    /**
     * Compute the hash of connection properties of the given cloud.
     * This hash is used to determine if a cloud was updated and a new connection is needed.
     *
     * <p>The current credential secret is included via {@link #tokenFingerprint(SlurmCloud)} so
     * that renewing a JWT (editing the credential in place, keeping the same credential ID)
     * changes the validity hash and invalidates the cached client. Without this, a cached
     * {@link SlurmClient} keeps serving the stale token — which pings fine on a freshly-built
     * client (Test Connection) but fails batch job submission with a protocol authentication
     * error — until the 30-minute cache TTL expires.
     *
     * @param cloud cloud to compute validity hash for
     * @return client validity hash code
     */
    @Restricted(NoExternalUse.class)
    public static int getValidity(@NonNull SlurmCloud cloud) {
        Object[] cloudObjects = {
            cloud.getSlurmRestApiUrl(),
            cloud.getCredentialsId(),
            cloud.getDefaultPartition(),
            tokenFingerprint(cloud)
        };
        return Arrays.hashCode(cloudObjects);
    }

    /**
     * Returns a stable, non-reversible fingerprint of the cloud's current credential secret,
     * or {@code null} when no token is configured/resolvable. Used only to detect that the
     * secret value changed; the raw token is never stored in the validity hash.
     *
     * @param cloud the cloud whose credential secret to fingerprint
     * @return SHA-256 hex digest of the token, or {@code null}
     */
    private static String tokenFingerprint(@NonNull SlurmCloud cloud) {
        String token = getAuthToken(cloud);
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is a required algorithm on every JVM; fall back to length so a changed
            // secret still (weakly) changes the fingerprint rather than silently caching.
            LOGGER.log(Level.WARNING, "SHA-256 unavailable for token fingerprint; using weak fallback", e);
            return "len:" + token.length();
        }
    }
    
    private static class Client {
        private final SlurmClient client;
        private final int validity;
        
        public Client(int validity, SlurmClient client) {
            this.client = client;
            this.validity = validity;
        }
        
        public SlurmClient getClient() {
            return client;
        }
        
        public int getValidity() {
            return validity;
        }
    }
    
    @Restricted(NoExternalUse.class) // testing only
    public static void invalidate(String displayName) {
        clients.invalidate(displayName);
    }
    
    @Restricted(NoExternalUse.class) // testing only
    public static void invalidateAll() {
        clients.invalidateAll();
    }
    
    /**
     * Listener that invalidates clients when cloud configuration changes.
     */
    @Extension
    public static class SaveableListenerImpl extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof Jenkins) {
                Jenkins jenkins = (Jenkins) o;
                Set<String> cloudDisplayNames = new HashSet<>(clients.asMap().keySet());
                
                for (SlurmCloud cloud : jenkins.clouds.getAll(SlurmCloud.class)) {
                    String displayName = cloud.getDisplayName();
                    Client client = clients.getIfPresent(displayName);
                    
                    if (client == null || client.getValidity() == getValidity(cloud)) {
                        cloudDisplayNames.remove(displayName);
                    }
                }
                
                // Remove missing / invalid clients
                for (String displayName : cloudDisplayNames) {
                    LOGGER.log(Level.INFO, "Invalidating Slurm client: " + displayName);
                    invalidate(displayName);
                }
            }
            super.onChange(o, file);
        }
    }
}
