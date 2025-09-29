package io.jenkins.plugins.slurm.client;

/**
 * Simple data class to hold SLURM controller information
 * This provides a clean interface for SLURM controller data without exposing OpenAPI-specific types
 */
public class SlurmPingInfo {
    private final String hostname;
    private final String version;
    private final String cluster;
    private final boolean responding;
    private final Long latency;
    
    public SlurmPingInfo(String hostname, String version, String cluster, boolean responding, Long latency) {
        this.hostname = hostname;
        this.version = version;
        this.cluster = cluster;
        this.responding = responding;
        this.latency = latency;
    }
    
    public String getHostname() { return hostname; }
    public String getVersion() { return version; }
    public String getCluster() { return cluster; }
    public boolean isResponding() { return responding; }
    public Long getLatency() { return latency; }
}