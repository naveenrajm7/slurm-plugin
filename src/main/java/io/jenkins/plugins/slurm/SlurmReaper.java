package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Main;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.slurm.client.ApiException;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;

/**
 * Removes Jenkins agents whose Slurm job is gone, finished, or no longer connected.
 *
 * <p>{@link SlurmGarbageCollection} only cancels orphan jobs. This is the other half, the same
 * role as the Kubernetes plugin {@code Reaper}: a failed or disconnected computer stays in
 * {@code Jenkins.getNodes()} and keeps counting toward the template instance cap until something
 * deletes the node. {@code terminate()} is supposed to do that, but a node save can put the agent
 * back, and {@code OnceRetentionStrategy} will not retry a launcher that has already failed.
 * A job that is still PENDING is left running. A permanently failed launch is removed after the
 * offline grace even if that job is still queued. {@code keepJobOnFailure} keeps a running allocation.
 */
@Extension
public class SlurmReaper extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(SlurmReaper.class.getName());

    /**
     * How long an offline computer may keep a Slurm job that is already RUNNING.
     * A job that is still PENDING is waiting for a machine and is never reaped.
     * Launch that is still inside {@code waitForAgentConnection} is not subject to this.
     */
    static final long OFFLINE_GRACE_MS = SystemProperties.getLong(
            SlurmReaper.class.getName() + ".offlineGraceMs", TimeUnit.SECONDS.toMillis(60));

    private static final long RECURRENCE_SECONDS = SystemProperties.getLong(
            SlurmReaper.class.getName() + ".recurrencePeriod", Main.isUnitTest ? 3600L : 60L);

    /** First time a computer was observed offline, keyed by node name. */
    private static final ConcurrentMap<String, Long> OFFLINE_SINCE = new ConcurrentHashMap<>();

    public SlurmReaper() {
        super("Reap stale Slurm agents");
    }

    @Override
    protected void execute(TaskListener listener) {
        // Other tests register offline Slurm agents that must stay put. They call reap() directly.
        if (Main.isUnitTest) {
            return;
        }
        reap(System.currentTimeMillis(), OFFLINE_GRACE_MS);
    }

    static void reap(long nowEpochMs, long offlineGraceMs) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Node node : new ArrayList<>(jenkins.getNodes())) {
            if (!(node instanceof SlurmAgent agent)) {
                continue;
            }
            seen.add(agent.getNodeName());
            try {
                reapAgent(jenkins, agent, nowEpochMs, offlineGraceMs);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to reap Slurm agent " + agent.getNodeName(), e);
            }
        }
        OFFLINE_SINCE.keySet().retainAll(seen);
    }

    static void clearForTest() {
        OFFLINE_SINCE.clear();
    }

    private static void reapAgent(
            @NonNull Jenkins jenkins, @NonNull SlurmAgent agent, long nowEpochMs, long offlineGraceMs) {
        Computer computer = agent.toComputer();
        if (!(computer instanceof SlurmComputer slurmComputer)) {
            return;
        }
        if (slurmComputer.isOnline() || slurmComputer.isLaunching()) {
            OFFLINE_SINCE.remove(agent.getNodeName());
            return;
        }

        String jobId = agent.getSlurmJobId();
        SlurmLauncher launcher = slurmLauncher(slurmComputer);
        boolean launchFailed =
                launcher != null && (launcher.isLaunchFailed() || launcher.getProblem() != null);
        boolean offlineWithCause = slurmComputer.getOfflineCause() != null;

        if (jobId == null || jobId.isBlank()) {
            // A node that has not been launched yet has no offline cause and no failure flag.
            if (launchFailed || offlineWithCause) {
                removeNode(jenkins, agent, null, false);
            }
            return;
        }

        SlurmCloud cloud = cloudOrNull(agent);
        JobLookup lookup = lookupJob(cloud, jobId);
        if (lookup.unknown || (lookup.pending && !launchFailed)) {
            // A launch that is still waiting for a machine, or a status we could not read.
            // Cancelling here would drop a job that may still be queued. A launch that has
            // already failed permanently is not this case: it falls through and is removed
            // after the offline grace so it cannot hold a template slot indefinitely.
            OFFLINE_SINCE.remove(agent.getNodeName());
            return;
        }
        if (lookup.missing || lookup.terminal) {
            removeNode(jenkins, agent, cloud, false);
            return;
        }

        // RUNNING, or a PENDING job whose launch already failed. Never cancel a job that
        // is still queued. A running allocation is kept when the template says so.
        boolean cancelJob = !lookup.pending && !keepsJobOnFailure(agent, cloud);
        if (launchFailed && !lookup.pending) {
            removeNode(jenkins, agent, cloud, cancelJob);
            return;
        }
        long offlineSince = OFFLINE_SINCE.computeIfAbsent(agent.getNodeName(), name -> nowEpochMs);
        if (nowEpochMs - offlineSince < offlineGraceMs) {
            return;
        }
        removeNode(jenkins, agent, cloud, cancelJob);
    }

    /** Template asked to leave the Slurm allocation up for debugging. The Jenkins node is still removed. */
    private static boolean keepsJobOnFailure(@NonNull SlurmAgent agent, @CheckForNull SlurmCloud cloud) {
        if (cloud == null) {
            return false;
        }
        SlurmJobTemplate template = cloud.getTemplateById(agent.getTemplateId());
        return template != null && template.isKeepJobOnFailure();
    }

    private static void removeNode(
            @NonNull Jenkins jenkins,
            @NonNull SlurmAgent agent,
            @CheckForNull SlurmCloud cloud,
            boolean cancelJob) {
        String jobId = agent.getSlurmJobId();
        if (cancelJob && jobId != null && cloud != null) {
            LOGGER.info("Cancelling Slurm job " + jobId + " for stale agent " + agent.getNodeName());
            cloud.cancelJob(jobId, TaskListener.NULL);
        }
        Node registered = jenkins.getNode(agent.getNodeName());
        if (registered == null) {
            OFFLINE_SINCE.remove(agent.getNodeName());
            return;
        }
        try {
            LOGGER.info("Removing stale Slurm agent " + agent.getNodeName()
                    + (jobId != null ? " (job " + jobId + ")" : ""));
            jenkins.removeNode(registered);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to remove Slurm agent " + agent.getNodeName(), e);
        }
        OFFLINE_SINCE.remove(agent.getNodeName());
    }

    @CheckForNull
    private static SlurmCloud cloudOrNull(@NonNull SlurmAgent agent) {
        try {
            return agent.getSlurmCloud();
        } catch (IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Cloud missing for " + agent.getNodeName() + ": " + e.getMessage());
            return null;
        }
    }

    @CheckForNull
    private static SlurmLauncher slurmLauncher(@NonNull SlaveComputer computer) {
        ComputerLauncher launcher = computer.getLauncher();
        if (launcher instanceof SlurmLauncher slurmLauncher) {
            return slurmLauncher;
        }
        return null;
    }

    @NonNull
    private static JobLookup lookupJob(@CheckForNull SlurmCloud cloud, @NonNull String jobId) {
        if (cloud == null) {
            return JobLookup.unknown();
        }
        try {
            SlurmJobStatus status = cloud.fetchJobStatus(jobId);
            if (status == null || status.isMissing()) {
                return JobLookup.missing();
            }
            String state = status.getState();
            if (SlurmClient.isTerminalState(state)) {
                return JobLookup.terminal();
            }
            // A missing state is how slurmrestd reports a job that has not started yet.
            if (state == null || "PENDING".equals(state)) {
                return JobLookup.pending();
            }
            return JobLookup.running();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return JobLookup.missing();
            }
            LOGGER.log(Level.FINE, "Could not read Slurm job " + jobId + " while reaping", e);
            return JobLookup.unknown();
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(RECURRENCE_SECONDS);
    }

    @Override
    public long getInitialDelay() {
        // One pass shortly after startup, for agents left behind by a previous process.
        return Main.isUnitTest ? TimeUnit.HOURS.toMillis(1) : TimeUnit.SECONDS.toMillis(15);
    }

    private static final class JobLookup {
        final boolean missing;
        final boolean terminal;
        final boolean pending;
        final boolean unknown;

        private JobLookup(boolean missing, boolean terminal, boolean pending, boolean unknown) {
            this.missing = missing;
            this.terminal = terminal;
            this.pending = pending;
            this.unknown = unknown;
        }

        static JobLookup missing() {
            return new JobLookup(true, false, false, false);
        }

        static JobLookup terminal() {
            return new JobLookup(false, true, false, false);
        }

        static JobLookup pending() {
            return new JobLookup(false, false, true, false);
        }

        static JobLookup running() {
            return new JobLookup(false, false, false, false);
        }

        static JobLookup unknown() {
            return new JobLookup(false, false, false, true);
        }
    }
}
