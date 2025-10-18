package io.jenkins.plugins.slurm.client;

/**
 * Simple data class to hold SLURM controller information from v0.0.43_controller_ping response.
 * This provides a clean interface for SLURM controller data without exposing OpenAPI-specific types.
 */
public class SlurmPingInfo {
    private final String hostname;      // Target for ping
    private final String pinged;        // Ping result
    private final Boolean responding;   // If ping RPC responded with pong from controller
    private final Long latency;         // Number of microseconds it took to successfully ping or timeout
    private final String mode;          // The operating mode of the responding slurmctld
    private final Boolean primary;      // Is responding slurmctld the primary controller
    private final String version;       // SLURM version
    private final String cluster;       // Cluster name
    
    public SlurmPingInfo(String hostname, String pinged, Boolean responding, Long latency, 
                         String mode, Boolean primary, String version, String cluster) {
        this.hostname = hostname;
        this.pinged = pinged;
        this.responding = responding;
        this.latency = latency;
        this.mode = mode;
        this.primary = primary;
        this.version = version;
        this.cluster = cluster;
    }
    
    public String getHostname() { return hostname; }
    public String getPinged() { return pinged; }
    public Boolean getResponding() { return responding; }
    public Long getLatency() { return latency; }
    public String getMode() { return mode; }
    public Boolean getPrimary() { return primary; }
    public String getVersion() { return version; }
    public String getCluster() { return cluster; }
}