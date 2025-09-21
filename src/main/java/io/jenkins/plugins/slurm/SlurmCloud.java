package io.jenkins.plugins.slurm;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * SLURM Cloud implementation for Jenkins.
 * 
 * This class represents a SLURM cluster as a Jenkins cloud provider,
 * allowing Jenkins to dynamically provision build agents by submitting
 * jobs to the SLURM workload manager.
 */
public class SlurmCloud extends AbstractCloudImpl {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmCloud.class.getName());
    
    private final String slurmControllerHost;
    private final int slurmControllerPort;
    private final String credentialsId;
    private final String defaultPartition;
    private final int maxAgents;
    private final int agentTimeoutMinutes;
    
    @DataBoundConstructor
    public SlurmCloud(String name,
                      String slurmControllerHost,
                      int slurmControllerPort,
                      String credentialsId,
                      String defaultPartition,
                      int maxAgents,
                      int agentTimeoutMinutes) {
        super(name, name);
        this.slurmControllerHost = slurmControllerHost;
        this.slurmControllerPort = slurmControllerPort > 0 ? slurmControllerPort : 22;
        this.credentialsId = credentialsId;
        this.defaultPartition = defaultPartition;
        this.maxAgents = maxAgents > 0 ? maxAgents : 10;
        this.agentTimeoutMinutes = agentTimeoutMinutes > 0 ? agentTimeoutMinutes : 60;
    }
    
    // Getters for configuration values
    public String getSlurmControllerHost() {
        return slurmControllerHost;
    }
    
    public int getSlurmControllerPort() {
        return slurmControllerPort;
    }
    
    public String getCredentialsId() {
        return credentialsId;
    }
    
    public String getDefaultPartition() {
        return defaultPartition;
    }
    
    public int getMaxAgents() {
        return maxAgents;
    }
    
    public int getAgentTimeoutMinutes() {
        return agentTimeoutMinutes;
    }
    
    public Collection<PlannedNode> provision(@CheckForNull Cloud.CloudState state,
                                           @NonNull Label label,
                                           int excessWorkload) {
        LOGGER.info("SLURM Cloud: Provision request for label=" + label + 
                   ", excessWorkload=" + excessWorkload);
        
        // TODO: Implement actual provisioning logic
        // For now, return empty collection
        return Collections.emptyList();
    }
    
    public boolean canProvision(@CheckForNull Cloud.CloudState state, @NonNull Label label) {
        // TODO: Implement logic to determine if we can provision for this label
        // For now, accept all labels
        return true;
    }
    
    /**
     * Gets the current number of SLURM agents.
     */
    private int getCurrentAgentCount() {
        int count = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof SlurmAgent) {
                SlurmAgent slurmAgent = (SlurmAgent) node;
                if (this.name.equals(slurmAgent.getCloudName())) {
                    count++;
                }
            }
        }
        return count;
    }
    
    @Extension
    @Symbol("slurm")
    public static class DescriptorImpl extends Descriptor<Cloud> {
        
        @Override
        public String getDisplayName() {
            return "SLURM";
        }
        
        public FormValidation doCheckSlurmControllerHost(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("SLURM controller hostname is required");
            }
            return FormValidation.ok();
        }
        
        public FormValidation doCheckSlurmControllerPort(@QueryParameter String value) {
            try {
                int port = Integer.parseInt(value);
                if (port <= 0 || port > 65535) {
                    return FormValidation.error("Port must be between 1 and 65535");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid port number");
            }
        }
        
        public FormValidation doCheckMaxAgents(@QueryParameter String value) {
            try {
                int max = Integer.parseInt(value);
                if (max <= 0) {
                    return FormValidation.error("Maximum agents must be greater than 0");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid number");
            }
        }
        
        public ListBoxModel doFillCredentialsIdItems() {
            // TODO: Implement credentials dropdown
            ListBoxModel items = new ListBoxModel();
            items.add("Select credentials...", "");
            return items;
        }
    }
}