package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Main;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import io.jenkins.plugins.slurm.client.ApiException;
import io.jenkins.plugins.slurm.client.SlurmClient;
import io.jenkins.plugins.slurm.client.SlurmJobStatus;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;

/**
 * Inverse of {@link SlurmGarbageCollection}: removes stale Jenkins agent nodes whose
 * backing Slurm job has already terminated.
 *
 * <p>While {@link SlurmGarbageCollection} cancels orphaned Slurm jobs that no longer have a
 * corresponding Jenkins agent, the Reaper removes Jenkins agent nodes that are offline because
 * their Slurm job has ended. Together they keep both sides in sync.
 *
 * <p>The Reaper only acts on agents that meet all of these conditions:
 * <ol>
 *   <li>The computer is offline (JNLP disconnected or never connected).
 *   <li>The computer is not in the middle of launching (avoids interfering with provisioning).
 *   <li>The agent has a recorded Slurm job ID.
 *   <li>A Slurm API check confirms the job is in a terminal state (or no longer exists).
 * </ol>
 *
 * <p>This prevents false-positive removals: an agent that is still launching (Slurm job
 * PENDING/RUNNING, JNLP not yet connected) will have {@code isLaunching() == true} and is
 * therefore skipped.
 */
@Extension
public class SlurmReaper extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(SlurmReaper.class.getName());

    /** Recurrence period in seconds (default 5 minutes; 30s in unit tests). */
    private static final long RECURRENCE_PERIOD_SECONDS = SystemProperties.getLong(
            SlurmReaper.class.getName() + ".recurrencePeriod",
            Main.isUnitTest ? 30 : TimeUnit.MINUTES.toSeconds(5));

    public SlurmReaper() {
        super("Reaping stale Slurm agent nodes");
    }

    @Override
    protected void execute(@NonNull TaskListener listener) throws IOException, InterruptedException {
        reapStaleNodes(listener);
    }

    /**
     * Scans all Jenkins computers for stale Slurm agents and terminates them.
     * Package-private for unit testing.
     */
    static void reapStaleNodes(@NonNull TaskListener listener) {
        for (Computer computer : Jenkins.get().getComputers()) {
            if (!(computer instanceof SlurmComputer)) {
                continue;
            }
            reapIfStale((SlurmComputer) computer, listener);
        }
    }

    /**
     * Checks a single {@link SlurmComputer} and terminates its agent if the Slurm job has ended.
     * Package-private for unit testing.
     */
    static void reapIfStale(@NonNull SlurmComputer computer, @NonNull TaskListener listener) {
        // Skip agents that are mid-launch — they haven't connected yet but the job is running.
        if (computer.isLaunching()) {
            LOGGER.fine("Skipping reap for " + computer.getName() + ": still launching");
            return;
        }

        // Only consider offline computers (online agents are healthy).
        if (computer.isOnline()) {
            return;
        }

        SlurmAgent agent = computer.getNode();
        if (agent == null) {
            return;
        }

        String jobId = agent.getSlurmJobId();
        if (jobId == null || jobId.isBlank()) {
            // Agent never got a Slurm job (e.g., failed before submission). Launcher should
            // have already cleaned it up; if not, leave it to the operator.
            return;
        }

        SlurmCloud cloud;
        try {
            cloud = agent.getSlurmCloud();
        } catch (IllegalStateException e) {
            // Cloud was deleted — agent is definitely stranded; remove it.
            LOGGER.info("Reaping stranded agent " + agent.getNodeName()
                    + ": its cloud no longer exists (" + e.getMessage() + ")");
            terminateAgent(agent, listener);
            return;
        }

        SlurmClient client;
        try {
            client = SlurmClientProvider.createClient(cloud);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Cannot create Slurm client for cloud " + cloud.name
                    + "; skipping reap for agent " + agent.getNodeName(), e);
            return;
        }
        if (client == null) {
            LOGGER.fine("No Slurm client available for cloud " + cloud.name
                    + "; skipping reap for agent " + agent.getNodeName());
            return;
        }

        SlurmJobStatus status;
        try {
            status = client.getJobStatus(jobId);
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                // Job is gone from Slurm — the agent is stranded.
                LOGGER.info("Reaping stranded agent " + agent.getNodeName()
                        + ": Slurm job " + jobId + " returned 404 (no longer in Slurm)");
                terminateAgent(agent, listener);
            } else {
                LOGGER.log(Level.FINE, "Slurm API error checking job " + jobId
                        + " for agent " + agent.getNodeName() + "; skipping", e);
            }
            return;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Unexpected error checking job " + jobId
                    + " for agent " + agent.getNodeName() + "; skipping", e);
            return;
        }

        if (status == null) {
            // getJobStatus returns null for 404 / empty response — same as above.
            LOGGER.info("Reaping stranded agent " + agent.getNodeName()
                    + ": Slurm job " + jobId + " is no longer visible in slurmrestd");
            terminateAgent(agent, listener);
            return;
        }

        String state = status.getState();
        if (state != null && SlurmClient.isTerminalState(state)) {
            LOGGER.info("Reaping stale agent " + agent.getNodeName()
                    + ": Slurm job " + jobId + " is in terminal state " + state);
            terminateAgent(agent, listener);
        } else {
            LOGGER.fine("Agent " + agent.getNodeName() + " is offline but Slurm job " + jobId
                    + " is still " + state + " — leaving in place");
        }
    }

    private static void terminateAgent(@NonNull SlurmAgent agent, @NonNull TaskListener listener) {
        listener.getLogger().println("[Slurm Reaper] Terminating stale agent: " + agent.getNodeName()
                + " (Slurm job " + agent.getSlurmJobId() + ")");
        try {
            agent.terminate();
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to terminate stale agent " + agent.getNodeName(), e);
            listener.getLogger()
                    .println("[Slurm Reaper] Failed to terminate " + agent.getNodeName() + ": " + e.getMessage());
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(RECURRENCE_PERIOD_SECONDS);
    }
}
