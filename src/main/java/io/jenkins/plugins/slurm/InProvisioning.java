package io.jenkins.plugins.slurm;

import static java.util.stream.Collectors.toSet;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Label;

import java.util.Set;

/**
 * Collects the Slurm agents currently in provisioning.
 * 
 * This extension point allows tracking of agents that are being provisioned to prevent
 * over-provisioning. Multiple implementations can exist (though typically only one per
 * cloud type), and they all contribute to the total count of agents being provisioned.
 * 
 * The default implementation ({@link DefaultInProvisioning}) queries actual node state
 * rather than maintaining separate tracking, making it more robust and self-healing.
 * 
 * Inspired by Kubernetes plugin's InProvisioning pattern.
 */
public abstract class InProvisioning implements ExtensionPoint {
    
    /**
     * Returns the agents names in provisioning according to all implementations of this 
     * extension point for the given label.
     * 
     * @param label the {@link Label} being checked.
     * @return the agents names in provisioning according to all implementations of this 
     *         extension point for the given label.
     */
    @NonNull
    public static Set<String> getAllInProvisioning(@CheckForNull Label label) {
        return all().stream()
                .flatMap(c -> c.getInProvisioning(label).stream())
                .collect(toSet());
    }
    
    /**
     * Gets the count of agents currently being provisioned for a given label across all implementations.
     * 
     * @param label The label to check
     * @return The total number of agents currently being provisioned for this label
     */
    public static int getInProvisioningCount(@CheckForNull Label label) {
        return getAllInProvisioning(label).size();
    }
    
    /**
     * Returns all registered implementations of this extension point.
     * 
     * @return all registered implementations
     */
    public static ExtensionList<InProvisioning> all() {
        return ExtensionList.lookup(InProvisioning.class);
    }
    
    /**
     * Returns the agents in provisioning for the current label.
     * 
     * @param label The label being checked
     * @return The agents names in provisioning for the current label.
     */
    @NonNull
    public abstract Set<String> getInProvisioning(Label label);
}
