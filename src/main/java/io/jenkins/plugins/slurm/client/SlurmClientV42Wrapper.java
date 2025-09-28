package io.jenkins.plugins.slurm.client;

import io.jenkins.plugins.slurm.client.v42.ApiClient;
import io.jenkins.plugins.slurm.client.v42.ApiException;
import io.jenkins.plugins.slurm.client.v42.api.SlurmApi;
import io.jenkins.plugins.slurm.client.v42.model.V0042OpenapiPingArrayResp;
import io.jenkins.plugins.slurm.client.v42.model.V0042ControllerPing;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Wrapper for SLURM API v0.0.42 client
 */
public class SlurmClientV42Wrapper implements SlurmClientWrapper {
    
    private final SlurmApi slurmApi;
    private final ApiClient apiClient;
    
    public SlurmClientV42Wrapper(String baseUrl, String token) {
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
            System.out.println("[DEBUG] SlurmClientV42Wrapper: Attempting ping with base URI: " + apiClient.getBaseUri());
            V0042OpenapiPingArrayResp response = slurmApi.slurmV0042GetPing();
            System.out.println("[DEBUG] SlurmClientV42Wrapper: Received response: " + (response != null ? "not null" : "null"));
            
            if (response == null) {
                throw new Exception("Received null response from SLURM v0.0.42 ping");
            }
            
            System.out.println("[DEBUG] SlurmClientV42Wrapper: Pings list: " + (response.getPings() != null ? "size=" + response.getPings().size() : "null"));
            
            if (response.getPings() == null || response.getPings().isEmpty()) {
                throw new Exception("No ping responses received from SLURM v0.0.42");
            }
            
            List<String> hostnames = response.getPings().stream()
                .map(V0042ControllerPing::getHostname)
                .filter(hostname -> hostname != null && !hostname.isEmpty())
                .collect(Collectors.toList());
                
            System.out.println("[DEBUG] SlurmClientV42Wrapper: Successfully extracted " + hostnames.size() + " hostnames: " + hostnames);
            return hostnames;
                
        } catch (Exception e) {
            System.out.println("[ERROR] SlurmClientV42Wrapper: Ping failed with exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("[ERROR] SlurmClientV42Wrapper: Root cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            throw new Exception("SLURM v0.0.42 ping failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String getApiVersion() {
        return "v0.0.42";
    }
}