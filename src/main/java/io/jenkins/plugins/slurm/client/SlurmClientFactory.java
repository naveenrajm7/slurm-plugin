package io.jenkins.plugins.slurm.client;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Factory for creating SLURM API clients based on API version
 */
public class SlurmClientFactory {
    
    public enum ApiVersion {
        V0_0_40("v0.0.40"),
        V0_0_41("v0.0.41"),
        V0_0_42("v0.0.42");
        
        private final String version;
        
        ApiVersion(String version) {
            this.version = version;
        }
        
        public String getVersion() {
            return version;
        }
        
        /**
         * Parse version string to enum
         */
        public static ApiVersion fromString(String version) {
            for (ApiVersion v : values()) {
                if (v.version.equals(version)) {
                    return v;
                }
            }
            throw new IllegalArgumentException("Unsupported SLURM API version: " + version);
        }
    }
    
    /**
     * Create a SLURM API client for the specified version
     */
    public static SlurmClientWrapper createClient(String baseUrl, String token, ApiVersion version) throws MalformedURLException {
        String fullUrl = buildApiUrl(baseUrl, version);
        
        switch (version) {
            case V0_0_40:
                return new SlurmClientV40Wrapper(fullUrl, token);
            case V0_0_41:
                return new SlurmClientV41Wrapper(fullUrl, token);
            case V0_0_42:
                return new SlurmClientV42Wrapper(fullUrl, token);
            default:
                throw new IllegalArgumentException("Unsupported API version: " + version);
        }
    }
    
    /**
     * Create a client with default version (latest supported)
     */
    public static SlurmClientWrapper createClient(String baseUrl, String token) throws MalformedURLException {
        return createClient(baseUrl, token, ApiVersion.V0_0_42);
    }
    
    /**
     * Auto-detect the SLURM API version by trying ping endpoints
     */
    public static SlurmClientWrapper createClientWithAutoDetection(String baseUrl, String token) throws Exception {
        // Try versions in descending order (newest first)
        ApiVersion[] versions = {ApiVersion.V0_0_42, ApiVersion.V0_0_41, ApiVersion.V0_0_40};
        
        System.out.println("[DEBUG] SlurmClientFactory: Starting auto-detection with base URL: " + baseUrl);
        
        List<String> errors = new ArrayList<>();
        for (ApiVersion version : versions) {
            try {
                System.out.println("[DEBUG] SlurmClientFactory: Trying " + version + " (" + version.getVersion() + ")");
                
                SlurmClientWrapper client = createClient(baseUrl, token, version);
                System.out.println("[DEBUG] SlurmClientFactory: Created client for " + version + ", testing connection...");
                
                // Test the connection with a ping
                List<String> hostnames = client.ping();
                System.out.println("[DEBUG] SlurmClientFactory: " + version + " ping returned " + (hostnames != null ? hostnames.size() : 0) + " hostnames");
                
                if (hostnames != null && !hostnames.isEmpty()) {
                    System.out.println("[SUCCESS] SlurmClientFactory: Successfully connected using " + version);
                    return client;
                } else {
                    errors.add(version.getVersion() + ": No hostnames returned from ping");
                }
            } catch (Exception e) {
                System.out.println("[ERROR] SlurmClientFactory: " + version + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                errors.add(version.getVersion() + ": " + e.getMessage());
                if (e.getCause() != null) {
                    System.out.println("[ERROR] SlurmClientFactory: " + version + " root cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
                }
            }
        }
        
        // If we reach here, all versions failed
        String errorMsg = "Failed to connect with any supported SLURM API version. Tried versions: " + 
                         java.util.Arrays.toString(versions) + ". Errors: " + String.join("; ", errors);
        System.out.println("[ERROR] SlurmClientFactory: " + errorMsg);
        throw new Exception(errorMsg);
    }
    
    private static String buildApiUrl(String baseUrl, ApiVersion version) throws MalformedURLException {
        URL base = new URL(baseUrl);
        String path = "/slurm/" + version.getVersion();
        return new URL(base.getProtocol(), base.getHost(), base.getPort(), path).toString();
    }
}