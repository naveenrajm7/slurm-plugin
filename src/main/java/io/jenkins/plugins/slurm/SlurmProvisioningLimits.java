package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Node;
import hudson.model.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;

/**
 * Atomic provisioning limits for Slurm clouds and job templates.
 *
 * <p>Mirrors {@code KubernetesProvisioningLimits}: reserves capacity before a {@link
 * hudson.slaves.NodeProvisioner.PlannedNode} is returned so concurrent provision rounds cannot
 * oversubscribe {@link SlurmCloud#getMaxAgents()} or template {@link SlurmJobTemplate#getInstanceCap()}.
 */
@Extension
public final class SlurmProvisioningLimits {
    private static final Logger LOGGER = Logger.getLogger(SlurmProvisioningLimits.class.getName());

    private final AtomicBoolean init = new AtomicBoolean();
    private final ConcurrentMap<String, Integer> cloudCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> templateCounts = new ConcurrentHashMap<>();

    public static SlurmProvisioningLimits get() {
        return ExtensionList.lookupSingleton(SlurmProvisioningLimits.class);
    }

    private boolean initInstance() {
        if (init.compareAndSet(false, true)) {
            Queue.withLock(() -> {
                synchronized (this) {
                    Jenkins.get().getNodes().stream()
                            .filter(SlurmAgent.class::isInstance)
                            .map(SlurmAgent.class::cast)
                            .forEach(node -> {
                                cloudCounts.put(
                                        node.getCloudName(),
                                        getCloudCount(node.getCloudName()) + node.getNumExecutors());
                                templateCounts.put(
                                        node.getTemplateId(),
                                        getTemplateCount(node.getTemplateId()) + node.getNumExecutors());
                            });
                }
            });
            return false;
        }
        return true;
    }

    /**
     * Reserve executor capacity for a pending agent.
     *
     * @return {@code true} if capacity was reserved
     */
    public boolean register(
            @NonNull SlurmCloud cloud, @NonNull SlurmJobTemplate template, int numExecutors) {
        initInstance();
        synchronized (this) {
            reconcileWithLiveNodes(cloud, template);
            int newCloudCount = getCloudCount(cloud.name) + numExecutors;
            if (newCloudCount > cloud.getMaxAgents()) {
                LOGGER.log(
                        Level.FINE,
                        () -> cloud.name + " cloud limit reached: " + getCloudCount(cloud.name) + "/"
                                + cloud.getMaxAgents());
                return false;
            }

            int newTemplateCount = getTemplateCount(template.getId()) + numExecutors;
            if (newTemplateCount > template.getInstanceCap()) {
                LOGGER.log(
                        Level.FINE,
                        () -> template.getName() + " template limit reached: "
                                + getTemplateCount(template.getId()) + "/" + template.getInstanceCap());
                return false;
            }

            cloudCounts.put(cloud.name, newCloudCount);
            templateCounts.put(template.getId(), newTemplateCount);
            return true;
        }
    }

    public void unregister(
            @NonNull SlurmCloud cloud, @NonNull SlurmJobTemplate template, int numExecutors) {
        if (!initInstance()) {
            return;
        }
        synchronized (this) {
            cloudCounts.put(cloud.name, Math.max(0, getCloudCount(cloud.name) - numExecutors));
            templateCounts.put(template.getId(), Math.max(0, getTemplateCount(template.getId()) - numExecutors));
        }
    }

    int getCloudCount(String cloudName) {
        return cloudCounts.getOrDefault(cloudName, 0);
    }

    int getTemplateCount(String templateId) {
        return templateCounts.getOrDefault(templateId, 0);
    }

    /**
     * Returns whether the cloud has reached {@link SlurmCloud#getMaxAgents()} capacity.
     * Reconciles with live Jenkins nodes so pre-existing agents are always counted.
     */
    boolean isCloudAtCapacity(@NonNull SlurmCloud cloud) {
        initInstance();
        synchronized (this) {
            reconcileCloudWithLiveNodes(cloud.name);
            return getCloudCount(cloud.name) >= cloud.getMaxAgents();
        }
    }

    /**
     * Returns whether the template has reached {@link SlurmJobTemplate#getInstanceCap()} capacity.
     */
    boolean isTemplateAtCapacity(@NonNull SlurmCloud cloud, @NonNull SlurmJobTemplate template) {
        initInstance();
        synchronized (this) {
            reconcileWithLiveNodes(cloud, template);
            return getTemplateCount(template.getId()) >= template.getInstanceCap();
        }
    }

    private void reconcileWithLiveNodes(@NonNull SlurmCloud cloud, @NonNull SlurmJobTemplate template) {
        reconcileCloudWithLiveNodes(cloud.name);
        reconcileTemplateWithLiveNodes(template.getId());
    }

    private void reconcileCloudWithLiveNodes(@NonNull String cloudName) {
        int liveCount = countLiveCloudExecutors(cloudName);
        if (getCloudCount(cloudName) < liveCount) {
            cloudCounts.put(cloudName, liveCount);
        }
    }

    private void reconcileTemplateWithLiveNodes(@NonNull String templateId) {
        int liveCount = countLiveTemplateExecutors(templateId);
        if (getTemplateCount(templateId) < liveCount) {
            templateCounts.put(templateId, liveCount);
        }
    }

    private static int countLiveCloudExecutors(@NonNull String cloudName) {
        int count = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof SlurmAgent) {
                SlurmAgent slurmAgent = (SlurmAgent) node;
                if (cloudName.equals(slurmAgent.getCloudName())) {
                    count += node.getNumExecutors();
                }
            }
        }
        return count;
    }

    private static int countLiveTemplateExecutors(@NonNull String templateId) {
        int count = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof SlurmAgent) {
                SlurmAgent slurmAgent = (SlurmAgent) node;
                if (templateId.equals(slurmAgent.getTemplateId())) {
                    count += node.getNumExecutors();
                }
            }
        }
        return count;
    }

    @Extension
    public static class NodeListenerImpl extends NodeListener {
        @Override
        protected void onDeleted(@NonNull Node node) {
            if (!(node instanceof SlurmAgent)) {
                return;
            }
            SlurmAgent slurmAgent = (SlurmAgent) node;
            hudson.slaves.Cloud cloud = Jenkins.get().getCloud(slurmAgent.getCloudName());
            if (!(cloud instanceof SlurmCloud)) {
                return;
            }
            SlurmCloud slurmCloud = (SlurmCloud) cloud;
            SlurmJobTemplate template = slurmCloud.getTemplateById(slurmAgent.getTemplateId());
            if (template != null) {
                SlurmProvisioningLimits.get().unregister(slurmCloud, template, node.getNumExecutors());
            }
        }
    }
}
