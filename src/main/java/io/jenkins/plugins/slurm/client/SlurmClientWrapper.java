package io.jenkins.plugins.slurm.client;

import java.util.List;

/**
 * Common interface for SLURM API clients across different versions
 */
public interface SlurmClientWrapper {
    
    /**
     * Test connection and get controller hostnames
     * @return List of controller hostnames
     * @throws Exception if ping fails
     */
    List<String> ping() throws Exception;
    
    /**
     * Get the API version this client supports
     * @return API version string
     */
    String getApiVersion();
}