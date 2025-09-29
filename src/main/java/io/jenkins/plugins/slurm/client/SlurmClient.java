package io.jenkins.plugins.slurm.client;

import io.jenkins.plugins.slurm.client.api.SlurmApi;
import io.jenkins.plugins.slurm.client.model.V0042OpenapiPingArrayResp;
import io.jenkins.plugins.slurm.client.model.V0042ControllerPing;
import io.jenkins.plugins.slurm.client.ApiClient;
import io.jenkins.plugins.slurm.client.ApiException;
import io.jenkins.plugins.slurm.client.Configuration;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Simplified SLURM REST API client for v0.0.42
 * This replaces the multi-version factory pattern with a direct client approach
 */
public class SlurmClient {
    private static final Logger LOGGER = Logger.getLogger(SlurmClient.class.getName());
    
    private final SlurmApi api;
    private final String baseUrl;
    
    public SlurmClient(String slurmRestApiUrl, String authToken) throws MalformedURLException {
        if (slurmRestApiUrl == null || slurmRestApiUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("SLURM REST API URL cannot be null or empty");
        }
        
        // Validate URL format
        new URL(slurmRestApiUrl); // throws MalformedURLException if invalid
        
        // Base URL should NOT include /slurm since OpenAPI-generated paths already include it
        // Remove trailing slash if present for consistency
        this.baseUrl = slurmRestApiUrl.endsWith("/") ? slurmRestApiUrl.substring(0, slurmRestApiUrl.length() - 1) : slurmRestApiUrl;
        
        // Create custom HttpClient to handle SLURM server response parsing
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)  // More lenient HTTP parsing
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL);
        
        // Create ApiClient with custom HttpClient to avoid HTTP parsing issues
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(httpClientBuilder);
        apiClient.updateBaseUri(this.baseUrl);
        
        LOGGER.info("ApiClient base path set to: " + this.baseUrl);
        
        if (authToken != null && !authToken.trim().isEmpty()) {
            apiClient.setRequestInterceptor(builder -> {
                // builder.header("X-SLURM-USER-NAME", "jenkins");
                builder.header("X-SLURM-USER-TOKEN", authToken);
                LOGGER.fine("Added authentication headers for SLURM REST API request");
            });
        }
        
        this.api = new SlurmApi(apiClient);
        LOGGER.info("SlurmClient initialized with base URL: " + this.baseUrl + " (API endpoints will use /slurm/v0.0.42/... paths)");
    }
    
    /**
     * Test connectivity by pinging the SLURM controller and return essential info
     * @return SLURM controller information (hostname and version) or null if failed
     */
    public SlurmPingInfo getSlurmInfo() {
        try {
            LOGGER.info("Attempting to ping SLURM controller at: " + baseUrl);
            LOGGER.info("Full expected URL will be: " + baseUrl + "/slurm/v0.0.42/ping/");
            
            V0042OpenapiPingArrayResp response = api.slurmV0042GetPing();
            
            if (response != null && response.getPings() != null && !response.getPings().isEmpty()) {
                V0042ControllerPing ping = response.getPings().get(0);
                String hostname = ping.getHostname();
                String version = "unknown";
                String cluster = "unknown";
                boolean responding = Boolean.TRUE.equals(ping.getResponding());
                Long latency = ping.getLatency();
                
                // Extract version and cluster info from metadata
                if (response.getMeta() != null && response.getMeta().getSlurm() != null) {
                    if (response.getMeta().getSlurm().getRelease() != null) {
                        version = response.getMeta().getSlurm().getRelease();
                    }
                    if (response.getMeta().getSlurm().getCluster() != null) {
                        cluster = response.getMeta().getSlurm().getCluster();
                    }
                }
                
                LOGGER.info(String.format("SLURM ping successful - hostname: %s, version: %s, cluster: %s, responding: %s, latency: %d μs", 
                           hostname, version, cluster, responding, latency));
                
                return new SlurmPingInfo(hostname, version, cluster, responding, latency);
            } else {
                LOGGER.warning("SLURM ping response received but no ping data found");
                return null;
            }
        } catch (ApiException e) {
            LOGGER.log(Level.SEVERE, "SLURM ping failed with API error: HTTP " + e.getCode() + 
                      " - " + e.getMessage() + " (Response: " + e.getResponseBody() + ")", e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "SLURM ping failed with unexpected error: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Test connectivity by pinging the SLURM controller
     * @return true if ping is successful, false otherwise
     */
    public boolean ping() {
        return getSlurmInfo() != null;
    }
    
    /**
     * Get the base URL for this client
     * @return the base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }
    
    /**
     * Get the underlying SLURM API instance for advanced operations
     * @return the SlurmApi instance
     */
    public SlurmApi getApi() {
        return api;
    }
}