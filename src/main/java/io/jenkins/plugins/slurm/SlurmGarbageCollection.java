package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Main;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.slurm.client.ApiException;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.model.JobInfo;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Garbage collection of orphaned Slurm jobs created by the plugin.
 *
 * <p>Mirrors the Kubernetes plugin {@code GarbageCollection} pattern: live agents refresh a
 * heartbeat for their Slurm job ID; periodic work cancels plugin jobs whose heartbeat is stale
 * and that are no longer owned by a Jenkins {@link SlurmAgent}.
 */
public class SlurmGarbageCollection extends AbstractDescribableImpl<SlurmGarbageCollection> {
    public static final int MINIMUM_GC_TIMEOUT_SECONDS = 300;

    private static final Logger LOGGER = Logger.getLogger(SlurmGarbageCollection.class.getName());

    private static final Long RECURRENCE_PERIOD = SystemProperties.getLong(
            SlurmGarbageCollection.class.getName() + ".recurrencePeriod",
            Main.isUnitTest ? 5 : TimeUnit.MINUTES.toSeconds(1));

    private int timeoutSeconds;

    @DataBoundConstructor
    public SlurmGarbageCollection() {}

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @DataBoundSetter
    public void setTimeoutSeconds(int timeoutSeconds) {
        if (Main.isUnitTest) {
            this.timeoutSeconds = timeoutSeconds;
        } else {
            this.timeoutSeconds = Math.max(timeoutSeconds, MINIMUM_GC_TIMEOUT_SECONDS);
        }
    }

    public Duration getDurationTimeout() {
        return Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : MINIMUM_GC_TIMEOUT_SECONDS);
    }

    static boolean isPluginJobName(@NonNull String cloudName, @CheckForNull String jobName) {
        return jobName != null && jobName.startsWith(cloudName + "-");
    }

    static boolean shouldCancelOrphan(
            @NonNull String jobId,
            @NonNull Set<String> liveJobIds,
            @NonNull Duration timeout,
            long nowEpochMs) {
        if (liveJobIds.contains(jobId)) {
            return false;
        }
        Long lastRefresh = SlurmJobHeartbeats.getLastRefresh(jobId);
        if (lastRefresh == null) {
            return false;
        }
        return Duration.between(Instant.ofEpochMilli(lastRefresh), Instant.ofEpochMilli(nowEpochMs))
                        .compareTo(timeout)
                > 0;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<SlurmGarbageCollection> {
        @SuppressWarnings("unused")
        public FormValidation doCheckTimeoutSeconds(@QueryParameter String value) {
            return FormValidation.validateIntegerInRange(value, MINIMUM_GC_TIMEOUT_SECONDS, Integer.MAX_VALUE);
        }
    }

    @Extension
    public static final class PeriodicGarbageCollection extends AsyncPeriodicWork {
        public PeriodicGarbageCollection() {
            super("Garbage collection of orphaned Slurm jobs");
        }

        @Override
        protected void execute(TaskListener listener) throws IOException, InterruptedException {
            refreshLiveAgents();
            garbageCollect();
        }

        static void refreshLiveAgents() {
            for (Computer computer : Jenkins.get().getComputers()) {
                if (!(computer instanceof SlurmComputer) || !computer.isOnline()) {
                    continue;
                }
                SlurmAgent agent = ((SlurmComputer) computer).getNode();
                if (agent == null) {
                    continue;
                }
                String jobId = agent.getSlurmJobId();
                if (jobId == null || jobId.isBlank()) {
                    continue;
                }
                SlurmJobHeartbeats.refresh(agent.getCloudName(), jobId, agent.getNodeName());
            }
        }

        static void garbageCollect() {
            for (SlurmCloud cloud : Jenkins.get().clouds.getAll(SlurmCloud.class)) {
                SlurmGarbageCollection gc = cloud.getGarbageCollection();
                if (gc == null) {
                    continue;
                }
                collectForCloud(cloud, gc);
            }
        }

        static void collectForCloud(@NonNull SlurmCloud cloud, @NonNull SlurmGarbageCollection gc) {
            Set<String> liveJobIds = liveJobIdsForCloud(cloud.name);
            Duration timeout = gc.getDurationTimeout();
            long now = System.currentTimeMillis();

            try {
                SlurmClient client = SlurmClientProvider.createClient(cloud);
                if (client == null) {
                    LOGGER.warning("Skipping Slurm GC for cloud " + cloud.name + ": no client");
                    return;
                }

                List<JobInfo> jobs = client.listJobs();
                for (JobInfo jobInfo : jobs) {
                    if (jobInfo.getJobId() == null) {
                        continue;
                    }
                    String jobId = String.valueOf(jobInfo.getJobId());
                    String jobName = jobInfo.getName();
                    if (!isPluginJobName(cloud.name, jobName)) {
                        continue;
                    }

                    String state = SlurmClient.resolveJobState(jobInfo);
                    if (state != null && SlurmClient.isTerminalState(state)) {
                        SlurmJobHeartbeats.remove(jobId);
                        continue;
                    }

                    if (!shouldCancelOrphan(jobId, liveJobIds, timeout, now)) {
                        continue;
                    }

                    LOGGER.info("Cancelling orphaned Slurm job " + jobId + " (" + jobName + ") on cloud "
                            + cloud.name);
                    try {
                        client.cancelJob(jobId);
                        SlurmJobHeartbeats.remove(jobId);
                    } catch (ApiException e) {
                        LOGGER.log(Level.WARNING, "Failed to cancel orphaned Slurm job " + jobId, e);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Unexpected error during Slurm garbage collection for " + cloud.name, e);
            }
        }

        @NonNull
        static Set<String> liveJobIdsForCloud(@NonNull String cloudName) {
            Set<String> liveJobIds = new HashSet<>();
            for (Node node : Jenkins.get().getNodes()) {
                if (!(node instanceof SlurmAgent)) {
                    continue;
                }
                SlurmAgent agent = (SlurmAgent) node;
                if (!Objects.equals(cloudName, agent.getCloudName())) {
                    continue;
                }
                if (agent.getSlurmJobId() != null) {
                    liveJobIds.add(agent.getSlurmJobId());
                }
            }
            return liveJobIds;
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(RECURRENCE_PERIOD);
        }
    }
}
