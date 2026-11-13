package io.jenkins.plugins.slurm;

/**
 * Dropwizard metric names published when the Metrics plugin is installed.
 *
 * <p>The Prometheus plugin scrapes this registry as-is. Names stay unlabeled so a cloud, template,
 * or job id cannot grow the registry without a bound.
 */
public final class SlurmMetricNames {

    private static final String PREFIX = "slurm.cloud";

    /** A Slurm job was submitted for an agent. */
    public static final String JOBS_SUBMITTED = PREFIX + ".jobs.submitted";

    /** The agent connected and can run a build. */
    public static final String AGENTS_CONNECTED = PREFIX + ".agents.connected";

    /** Launch failed, including timeouts. */
    public static final String LAUNCH_FAILED = PREFIX + ".launch.failed";

    /** Subset of {@link #LAUNCH_FAILED}: the agent did not connect before the timeout. */
    public static final String LAUNCH_FAILED_TIMEOUT = PREFIX + ".launch.failed.timeout";

    /** This plugin cancelled a Slurm job. */
    public static final String JOBS_CANCELLED = PREFIX + ".jobs.cancelled";

    /** Time from a successful submit until the agent connects. */
    public static final String LAUNCH_DURATION = PREFIX + ".launch.duration";

    private SlurmMetricNames() {}
}
