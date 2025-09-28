package io.jenkins.plugins.slurm.client;

import io.jenkins.plugins.slurm.client.v40.ApiClient;
import io.jenkins.plugins.slurm.client.v40.ApiException;
import io.jenkins.plugins.slurm.client.v40.api.SlurmApi;
import io.jenkins.plugins.slurm.client.v40.model.V0040OpenapiPingArrayResp;
import io.jenkins.plugins.slurm.client.v40.model.V0040ControllerPing;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Wrapper for SLURM API v0.0.40 client
 */
public class SlurmClientV40Wrapper implements SlurmClientWrapper {
    
    private final SlurmApi slurmApi;
    private final ApiClient apiClient;
    
    public SlurmClientV40Wrapper(String baseUrl, String token) {
        this.apiClient = new ApiClient();
        this.apiClient.setBasePath(baseUrl);
        
        // Set Authorization header using request interceptor
        this.apiClient.setRequestInterceptor(builder -> 
            builder.header("Authorization", "Bearer " + token)
        );
        
        this.slurmApi = new SlurmApi(apiClient);
    }
    
    @Override
    public List<String> ping() throws Exception {
        try {
            System.out.println("[DEBUG] SlurmClientV40Wrapper: Attempting ping with base URI: " + apiClient.getBaseUri());
            V0040OpenapiPingArrayResp response = slurmApi.slurmV0040GetPing();
            System.out.println("[DEBUG] SlurmClientV40Wrapper: Received response: " + (response != null ? "not null" : "null"));
            
            if (response == null) {
                throw new Exception("Received null response from SLURM v0.0.40 ping");
            }
            
            System.out.println("[DEBUG] SlurmClientV40Wrapper: Pings list: " + (response.getPings() != null ? "size=" + response.getPings().size() : "null"));
            
            if (response.getPings() == null || response.getPings().isEmpty()) {
                throw new Exception("No ping responses received from SLURM v0.0.40");
            }
            
            List<String> hostnames = response.getPings().stream()
                .map(V0040ControllerPing::getHostname)
                .filter(hostname -> hostname != null && !hostname.isEmpty())
                .collect(Collectors.toList());
                
            System.out.println("[DEBUG] SlurmClientV40Wrapper: Successfully extracted " + hostnames.size() + " hostnames: " + hostnames);
            return hostnames;
                
        } catch (Exception e) {
            System.out.println("[ERROR] SlurmClientV40Wrapper: Ping failed with exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("[ERROR] SlurmClientV40Wrapper: Root cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            throw new Exception("SLURM v0.0.40 ping failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String getApiVersion() {
        return "v0.0.40";
    }
}