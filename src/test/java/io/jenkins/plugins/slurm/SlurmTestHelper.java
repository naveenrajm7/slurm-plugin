package io.jenkins.plugins.slurm;

import hudson.model.Node;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

/** Shared fixtures for Slurm plugin integration tests. */
final class SlurmTestHelper {

    private SlurmTestHelper() {}

    static SlurmCloud createCloud(String name, int maxAgents) {
        return new SlurmCloud(name, "http://localhost:6820", null, "compute", maxAgents, 60);
    }

    static SlurmJobTemplate createTemplate(String name, String label, int instanceCap) {
        SlurmJobTemplate template = new SlurmJobTemplate();
        template.setName(name);
        template.setLabel(label);
        template.setPartition("compute");
        template.setInstanceCap(instanceCap);
        return template;
    }

    static SlurmCloud registerCloudWithTemplate(
            jenkins.model.Jenkins jenkins, String cloudName, int maxAgents, SlurmJobTemplate template) {
        SlurmCloud cloud = createCloud(cloudName, maxAgents);
        cloud.setJobTemplates(Collections.singletonList(template));
        jenkins.clouds.add(cloud);
        return cloud;
    }

    static SlurmAgent createAgent(String agentName, String cloudName, String templateId) throws Exception {
        return new SlurmAgent(
                agentName,
                "test agent",
                "/tmp",
                1,
                Node.Mode.NORMAL,
                "linux",
                new SlurmLauncher(),
                RetentionStrategy.INSTANCE,
                Collections.emptyList(),
                cloudName,
                templateId,
                "compute",
                new ProvisioningActivity.Id(cloudName, "template", agentName));
    }
}
