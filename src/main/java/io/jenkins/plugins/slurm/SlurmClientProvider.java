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
        final Client c = clients.getIfPresent(displayName);
        
        if (c == null) {
            // Create new client
            String authToken = getAuthToken(cloud);
            SlurmClient client = new SlurmClient(cloud.getSlurmRestApiUrl(), authToken);
            
            clients.put(displayName, new Client(getValidity(cloud), client));
            LOGGER.log(Level.FINE, "Created new Slurm client: {0}", displayName);
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
     * @param cloud cloud to compute validity hash for
     * @return client validity hash code
     */
    @Restricted(NoExternalUse.class)
    public static int getValidity(@NonNull SlurmCloud cloud) {
        Object[] cloudObjects = {
            cloud.getSlurmRestApiUrl(),
            cloud.getCredentialsId(),
            cloud.getDefaultPartition()
        };
        return Arrays.hashCode(cloudObjects);
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
