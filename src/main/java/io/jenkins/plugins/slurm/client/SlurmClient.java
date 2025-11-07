package io.jenkins.plugins.slurm.client;

import io.jenkins.plugins.slurm.client.api.SlurmApi;
import io.jenkins.plugins.slurm.client.model.OpenapiPingArrayResp;
import io.jenkins.plugins.slurm.client.model.ControllerPing;
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
 * Simplified Slurm REST API client for v0.0.42
 * This replaces the multi-version factory pattern with a direct client approach
 */
public class SlurmClient {
    private static final Logger LOGGER = Logger.getLogger(SlurmClient.class.getName());
    
    private final SlurmApi api;
    private final String baseUrl;
    
    public SlurmClient(String slurmRestApiUrl, String authToken) throws MalformedURLException {
        if (slurmRestApiUrl == null || slurmRestApiUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Slurm REST API URL cannot be null or empty");
        }
        
        // Validate URL format
        new URL(slurmRestApiUrl); // throws MalformedURLException if invalid
        
        // Base URL should NOT include /slurm since OpenAPI-generated paths already include it
        // Remove trailing slash if present for consistency
        this.baseUrl = slurmRestApiUrl.endsWith("/") ? slurmRestApiUrl.substring(0, slurmRestApiUrl.length() - 1) : slurmRestApiUrl;
        
        // Create custom HttpClient to handle Slurm server response parsing
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
                // builder.header("X-Slurm-USER-NAME", "jenkins");
                builder.header("X-Slurm-USER-TOKEN", authToken);
                LOGGER.fine("Added authentication headers for Slurm REST API request");
            });
        }
        
        this.api = new SlurmApi(apiClient);
        LOGGER.info("SlurmClient initialized with base URL: " + this.baseUrl + " (API endpoints will use /slurm/v0.0.42/... paths)");
    }
    
    /**
     * Test connectivity by pinging the Slurm controller and return detailed info
     * @return Slurm controller information from v0.0.43_controller_ping response or null if failed
     */
    public SlurmPingInfo getSlurmInfo() {
        try {
            LOGGER.info("Attempting to ping Slurm controller at: " + baseUrl);
            LOGGER.info("Full expected URL will be: " + baseUrl + "/slurm/v0.0.42/ping/");
            
            OpenapiPingArrayResp response = api.slurmGetPing();
            
            if (response != null && response.getPings() != null && !response.getPings().isEmpty()) {
                ControllerPing ping = response.getPings().get(0);
                
                // Extract all fields from v0.0.43_controller_ping
                String hostname = ping.getHostname();           // Target for ping
                String pinged = ping.getPinged();               // Ping result
                Boolean responding = ping.getResponding();      // If ping RPC responded with pong from controller
                Long latency = ping.getLatency();               // Number of microseconds it took to successfully ping or timeout
                String mode = ping.getMode() != null ? ping.getMode().toString() : null;  // Operating mode of responding slurmctld
                Boolean primary = ping.getPrimary();            // Is responding slurmctld the primary controller
                
                String version = "unknown";
                String cluster = "unknown";
                
                // Extract version and cluster info from metadata
                if (response.getMeta() != null && response.getMeta().getSlurm() != null) {
                    if (response.getMeta().getSlurm().getRelease() != null) {
                        version = response.getMeta().getSlurm().getRelease();
                    }
                    if (response.getMeta().getSlurm().getCluster() != null) {
                        cluster = response.getMeta().getSlurm().getCluster();
                    }
                }
                
                LOGGER.info(String.format("Slurm ping - hostname: %s, pinged: %s, responding: %s, latency: %d μs, mode: %s, primary: %s, version: %s, cluster: %s", 
                           hostname, pinged, responding, latency, mode, primary, version, cluster));
                
                return new SlurmPingInfo(hostname, pinged, responding, latency, mode, primary, version, cluster);
            } else {
                LOGGER.warning("Slurm ping response received but no ping data found");
                return null;
            }
        } catch (ApiException e) {
            LOGGER.log(Level.SEVERE, "Slurm ping failed with API error: HTTP " + e.getCode() + 
                      " - " + e.getMessage() + " (Response: " + e.getResponseBody() + ")", e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Slurm ping failed with unexpected error: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Test connectivity by pinging the Slurm controller
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
     * Get the underlying Slurm API instance for advanced operations
     * @return the SlurmApi instance
     */
    public SlurmApi getApi() {
        return api;
    }
    
    /**
     * Submit a job to Slurm
     * @param submitReq The job submission request containing job description
     * @return The job submission response with job ID
     * @throws ApiException if submission fails
     */
    public io.jenkins.plugins.slurm.client.model.OpenapiJobSubmitResponse submitJob(
            io.jenkins.plugins.slurm.client.model.JobSubmitReq submitReq) throws ApiException {
        
        if (submitReq == null || submitReq.getJob() == null) {
            throw new IllegalArgumentException("Job submission request and job description cannot be null");
        }
        
        LOGGER.info("Submitting job to Slurm: " + submitReq.getJob().getName());
        LOGGER.fine("Job details - partition: " + submitReq.getJob().getPartition() + 
                   ", CPUs: " + submitReq.getJob().getCpusPerTask());
        
        try {
            io.jenkins.plugins.slurm.client.model.OpenapiJobSubmitResponse response = 
                api.slurmPostJobSubmit(submitReq);
            
            if (response != null) {
                if (response.getJobId() != null) {
                    LOGGER.info("Job submitted successfully with ID: " + response.getJobId());
                } else if (response.getErrors() != null && !response.getErrors().isEmpty()) {
                    LOGGER.warning("Job submission returned errors:");
                    for (io.jenkins.plugins.slurm.client.model.OpenapiError error : response.getErrors()) {
                        LOGGER.warning("  - " + error.getError());
                    }
                }
                return response;
            } else {
                throw new ApiException("Job submission returned null response");
            }
            
        } catch (ApiException e) {
            LOGGER.log(Level.SEVERE, 
                      "Job submission failed with API error: HTTP " + e.getCode() + 
                      " - " + e.getMessage() + " (Response: " + e.getResponseBody() + ")", e);
            throw e;
        }
    }
    
    /**
     * Cancel a Slurm job by job ID
     * @param jobId The Slurm job ID to cancel
     * @throws ApiException if cancellation fails
     */
    public void cancelJob(String jobId) throws ApiException {
        if (jobId == null || jobId.isEmpty()) {
            throw new IllegalArgumentException("Job ID cannot be null or empty");
        }
        
        LOGGER.info("Canceling Slurm job: " + jobId);
        
        try {
            // The API expects job ID as a string
            io.jenkins.plugins.slurm.client.model.OpenapiKillJobResp response = 
                api.slurmDeleteJob(jobId, null, null);
            
            if (response != null) {
                if (response.getErrors() != null && !response.getErrors().isEmpty()) {
                    LOGGER.warning("Job cancellation returned errors:");
                    for (io.jenkins.plugins.slurm.client.model.OpenapiError error : response.getErrors()) {
                        LOGGER.warning("  - " + error.getError());
                    }
                } else {
                    LOGGER.info("Job " + jobId + " canceled successfully");
                }
            }
            
        } catch (ApiException e) {
            LOGGER.log(Level.WARNING, 
                      "Job cancellation failed with API error: HTTP " + e.getCode() + 
                      " - " + e.getMessage() + " (Response: " + e.getResponseBody() + ")", e);
            throw e;
        }
    }
}
