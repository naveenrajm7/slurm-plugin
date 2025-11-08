package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Default implementation of InProvisioning that queries actual node state.
 * 
 * This approach is more robust than manual tracking because:
 * - It reflects the actual state of nodes in Jenkins
 * - Self-healing: automatically corrects if tracking gets out of sync
 * - No need to maintain separate tracking maps
 * - Fewer places where bugs can be introduced
 * 
 * A node is considered "in provisioning" if it:
 * 1. Is a SlurmAgent instance
 * 2. Has a computer that either:
 *    - Has not launched yet (isLaunchSupported=true), OR
 *    - Is not accepting tasks yet (isAcceptingTasks=false)
 */
@Extension
public class DefaultInProvisioning extends InProvisioning {
    private static final Logger LOGGER = Logger.getLogger(DefaultInProvisioning.class.getName());

    /**
     * Checks if a node is not yet ready to accept tasks.
     * This indicates the node is still in the provisioning phase.
     * 
     * @param n The node to check
     * @return true if the node is still provisioning (not ready)
     */
    private static boolean isNotAcceptingTasks(Node n) {
        Computer computer = n.toComputer();
        return computer != null
                && (computer.isLaunchSupported() // Launcher hasn't been called yet
                        || !n.isAcceptingTasks()) // node is not ready yet
        ;
    }

    @Override
    public Set<String> getInProvisioning(@CheckForNull Label label) {
        if (label != null) {
            return label.getNodes().stream()
                    .filter(SlurmAgent.class::isInstance)
                    .filter(DefaultInProvisioning::isNotAcceptingTasks)
                    .map(Node::getNodeName)
                    .collect(Collectors.toSet());
        } else {
            // For unlabeled nodes, check all nodes
            return jenkins.model.Jenkins.get().getNodes().stream()
                    .filter(SlurmAgent.class::isInstance)
                    .filter(node -> node.getLabelString() == null || node.getLabelString().isEmpty())
                    .filter(DefaultInProvisioning::isNotAcceptingTasks)
                    .map(Node::getNodeName)
                    .collect(Collectors.toSet());
        }
    }
}
