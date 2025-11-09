package io.jenkins.plugins.slurm;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

@Extension
@SuppressWarnings({"rawtypes"})
public class SlurmQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
        if (node instanceof SlurmAgent) {
            SlurmAgent agent = (SlurmAgent) node;
            Task ownerTask = item.task.getOwnerTask();
            if (!SlurmFolderProperty.isAllowed(agent, (Job) ownerTask)) {
                return new SlurmCloudNotAllowed(agent.getSlurmCloud(), (Job) ownerTask);
            }
        }
        return null;
    }

    public static final class SlurmCloudNotAllowed extends CauseOfBlockage {

        private final SlurmCloud cloud;
        private final Job job;

        public SlurmCloudNotAllowed(SlurmCloud cloud, Job job) {
            this.cloud = cloud;
            this.job = job;
        }

        @Override
        public String getShortDescription() {
            return Messages.SlurmCloudNotAllowed_Description(cloud.name, job.getFullName());
        }
    }
}
