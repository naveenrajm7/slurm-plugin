package io.jenkins.plugins.slurm.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.slurm.client.api.SlurmApi;
import io.jenkins.plugins.slurm.client.api.SlurmdbApi;
import io.jenkins.plugins.slurm.client.model.ControllerPing;
import io.jenkins.plugins.slurm.client.model.Job;
import io.jenkins.plugins.slurm.client.model.JobInfo;
import io.jenkins.plugins.slurm.client.model.JobRes;
import io.jenkins.plugins.slurm.client.model.JobResNode;
import io.jenkins.plugins.slurm.client.model.JobResNodes1;
import io.jenkins.plugins.slurm.client.model.JobState1;
import io.jenkins.plugins.slurm.client.model.OpenapiPingArrayResp;
import io.jenkins.plugins.slurm.client.model.OpenapiSlurmdbdJobsResp;
import io.jenkins.plugins.slurm.client.model.ProcessExitCodeVerbose;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Slurm REST API client.
 *
 * <p>The request and response types are generated from one reference spec
 * ({@link SlurmApiVersion#REFERENCE}). The version segment of each request path is chosen per
 * client: either the version the cloud is configured with, or the newest supported version the
 * server answers on. Detection runs on the first call, not in the constructor, so building a
 * client never touches the network.
 */
public class SlurmClient {
    private static final Logger LOGGER = Logger.getLogger(SlurmClient.class.getName());

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Status for "the server serves none of our versions". Not 404, which callers read as a missing
     * job, and not a 4xx, which the launcher reads as Slurm rejecting the job.
     */
    static final int NO_SUPPORTED_VERSION_STATUS = 501;

    private final SlurmApi api;
    private final SlurmdbApi slurmdbApi;
    private final String baseUrl;
    private final String authToken;
    private final boolean versionConfigured;

    /** Null until the first call when the version is detected. */
    private volatile SlurmApiVersion apiVersion;

    /** Client that detects the API version on first use. */
    public SlurmClient(String slurmRestApiUrl, String authToken) throws MalformedURLException {
        this(slurmRestApiUrl, authToken, null);
    }

    /**
     * @param configuredVersion the version to use, or {@code null} to detect the newest version
     *     the server serves
     */
    public SlurmClient(String slurmRestApiUrl, String authToken, @CheckForNull SlurmApiVersion configuredVersion)
            throws MalformedURLException {
        if (slurmRestApiUrl == null || slurmRestApiUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Slurm REST API URL cannot be null or empty");
        }

        // Validate URL format
        new URL(slurmRestApiUrl); // throws MalformedURLException if invalid

        // Base URL should NOT include /slurm since OpenAPI-generated paths already include it
        // Remove trailing slash if present for consistency
        this.baseUrl = slurmRestApiUrl.endsWith("/")
                ? slurmRestApiUrl.substring(0, slurmRestApiUrl.length() - 1)
                : slurmRestApiUrl;

        this.authToken = authToken != null && !authToken.trim().isEmpty() ? authToken : null;
        this.versionConfigured = configuredVersion != null;
        this.apiVersion = configuredVersion;

        // Create ApiClient with custom HttpClient to avoid HTTP parsing issues
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(newHttpClientBuilder());
        apiClient.updateBaseUri(this.baseUrl);

        // The generated code sets the request URI before it calls the interceptor, so the
        // interceptor can replace the reference version in the path with the selected one.
        apiClient.setRequestInterceptor(builder -> {
            if (this.authToken != null) {
                // builder.header("X-Slurm-USER-NAME", "jenkins");
                builder.header("X-Slurm-USER-TOKEN", this.authToken);
            }
            SlurmApiVersion selected = this.apiVersion;
            if (selected != null && selected != SlurmApiVersion.REFERENCE) {
                builder.uri(selected.rewrite(builder.copy().build().uri()));
            }
        });

        this.api = new SlurmApi(apiClient);
        this.slurmdbApi = new SlurmdbApi(apiClient);
        LOGGER.fine(() -> "SlurmClient initialized with base URL: " + this.baseUrl + ", API version: "
                + (configuredVersion != null ? configuredVersion : "detect on first use"));
    }

    private static HttpClient.Builder newHttpClientBuilder() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // More lenient HTTP parsing
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
    }

    /**
     * The REST API version this client sends requests to. The first call on a client without a
     * configured version asks the server; a failed detection is not remembered, so the next call
     * tries again.
     *
     * @throws ApiException if the server cannot be reached, rejects the token, or serves none of
     *     the supported versions
     */
    @NonNull
    public SlurmApiVersion getApiVersion() throws ApiException {
        SlurmApiVersion selected = apiVersion;
        if (selected != null) {
            return selected;
        }
        synchronized (this) {
            if (apiVersion == null) {
                apiVersion = detectApiVersion();
                LOGGER.info("Slurm REST API at " + baseUrl + ": using " + apiVersion);
            }
            return apiVersion;
        }
    }

    /** True when the version came from the cloud configuration and not from detection. */
    public boolean isApiVersionConfigured() {
        return versionConfigured;
    }

    /**
     * Pings each supported version, newest first, and returns the first one the server has. A 404
     * means slurmrestd does not serve that version. Any other failure stops detection: it says
     * nothing about which versions exist.
     */
    @NonNull
    private SlurmApiVersion detectApiVersion() throws ApiException {
        HttpClient httpClient = newHttpClientBuilder().build();
        for (SlurmApiVersion candidate : SlurmApiVersion.newestFirst()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(candidate.pingUri(baseUrl))
                    .timeout(CONNECT_TIMEOUT)
                    .GET();
            if (authToken != null) {
                request.header("X-Slurm-USER-TOKEN", authToken);
            }
            HttpResponse<String> response;
            try {
                response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new ApiException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(e);
            }
            int status = response.statusCode();
            if (status / 100 == 2) {
                return candidate;
            }
            if (status != 404) {
                throw new ApiException(
                        status,
                        "Slurm REST API version check failed on " + candidate.pingUri(baseUrl) + ": HTTP " + status,
                        response.headers(),
                        response.body());
            }
            LOGGER.fine(() -> "slurmrestd at " + baseUrl + " does not serve " + candidate);
        }
        throw new ApiException(
                NO_SUPPORTED_VERSION_STATUS,
                "slurmrestd at " + baseUrl + " serves none of the REST API versions this plugin supports ("
                        + SlurmApiVersion.supportedWireNames()
                        + "). Check the URL, or use a plugin release that supports this Slurm release.");
    }

    /**
     * Test connectivity by pinging the Slurm controller and return detailed info
     * @return Slurm controller information from the controller ping response, or null if failed
     */
    public SlurmPingInfo getSlurmInfo() {
        try {
            SlurmApiVersion pingVersion = getApiVersion();
            LOGGER.fine(() -> "Pinging Slurm controller at " + pingVersion.pingUri(baseUrl));

            OpenapiPingArrayResp response = api.slurmGetPing();

            if (response != null
                    && response.getPings() != null
                    && !response.getPings().isEmpty()) {
                ControllerPing ping = response.getPings().get(0);

                // Extract all fields from controller_ping
                String hostname = ping.getHostname(); // Target for ping
                String pinged = ping.getPinged(); // Ping result
                Boolean responding = ping.getResponding(); // If ping RPC responded with pong from controller
                Long latency = ping.getLatency(); // Number of microseconds it took to successfully ping or timeout
                String mode = ping.getMode() != null
                        ? ping.getMode().toString()
                        : null; // Operating mode of responding slurmctld
                Boolean primary = ping.getPrimary(); // Is responding slurmctld the primary controller

                String version = "unknown";
                String cluster = "unknown";

                // Extract version and cluster info from metadata
                if (response.getMeta() != null && response.getMeta().getSlurm() != null) {
                    if (response.getMeta().getSlurm().getRelease() != null) {
                        version = response.getMeta().getSlurm().getRelease();
                    }
                    if (response.getMeta().getSlurm().getCluster() != null) {
                        cluster = response.getMeta().getSlurm().getCluster();
                    }
                }

                LOGGER.info(String.format(
                        "Slurm ping - hostname: %s, pinged: %s, responding: %s, latency: %d μs, mode: %s, primary: %s, version: %s, cluster: %s",
                        hostname, pinged, responding, latency, mode, primary, version, cluster));

                return new SlurmPingInfo(hostname, pinged, responding, latency, mode, primary, version, cluster);
            } else {
                LOGGER.warning("Slurm ping response received but no ping data found");
                return null;
            }
        } catch (ApiException e) {
            LOGGER.log(
                    Level.SEVERE,
                    "Slurm ping failed with API error: HTTP " + e.getCode() + " - " + e.getMessage() + " (Response: "
                            + e.getResponseBody() + ")",
                    e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Slurm ping failed with unexpected error: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Test connectivity by pinging the Slurm controller
     * @return true if ping is successful, false otherwise
     */
    public boolean ping() {
        return getSlurmInfo() != null;
    }

    /**
     * Get the base URL for this client
     * @return the base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Get the underlying Slurm API instance for advanced operations
     * @return the SlurmApi instance
     */
    public SlurmApi getApi() {
        return api;
    }

    /**
     * Submit a job to Slurm
     * @param submitReq The job submission request containing job description
     * @return The job submission response with job ID
     * @throws ApiException if submission fails
     */
    public io.jenkins.plugins.slurm.client.model.OpenapiJobSubmitResponse submitJob(
            io.jenkins.plugins.slurm.client.model.JobSubmitReq submitReq) throws ApiException {

        if (submitReq == null || submitReq.getJob() == null) {
            throw new IllegalArgumentException("Job submission request and job description cannot be null");
        }

        getApiVersion();
        LOGGER.info("Submitting job to Slurm: " + submitReq.getJob().getName());
        LOGGER.fine("Job details - partition: " + submitReq.getJob().getPartition() + ", CPUs: "
                + submitReq.getJob().getCpusPerTask());

        try {
            io.jenkins.plugins.slurm.client.model.OpenapiJobSubmitResponse response = api.slurmPostJobSubmit(submitReq);

            if (response != null) {
                if (response.getJobId() != null) {
                    LOGGER.info("Job submitted successfully with ID: " + response.getJobId());
                } else if (response.getErrors() != null && !response.getErrors().isEmpty()) {
                    LOGGER.warning("Job submission returned errors:");
                    for (io.jenkins.plugins.slurm.client.model.OpenapiError error : response.getErrors()) {
                        LOGGER.warning("  - " + error.getError());
                    }
                }
                return response;
            } else {
                throw new ApiException("Job submission returned null response");
            }

        } catch (ApiException e) {
            LOGGER.log(
                    Level.SEVERE,
                    "Job submission failed with API error: HTTP " + e.getCode() + " - " + e.getMessage()
                            + " (Response: " + e.getResponseBody() + ")",
                    e);
            throw e;
        }
    }

    /**
     * Cancel a Slurm job by job ID.
     *
     * <p>Uses the <b>plural</b> {@code DELETE /slurm/<version>/jobs/} endpoint
     * ({@code slurm_delete_jobs}) rather than the singular
     * {@code DELETE /slurm/<version>/job/{job_id}} endpoint. The singular handler
     * ({@code op_handler_job}) rejects any job ID {@code >= MAX_JOB_ID}
     * (0x03FFFFFF / 67,108,863) <em>before</em> contacting {@code slurmctld}, and
     * federated Slurm encodes the origin cluster ID in the top 6 bits of the job
     * ID, so every job on a federation member cluster has a raw ID above that
     * bound and is rejected with {@code 2017 Invalid JobID}. The plural handler
     * ({@code _signal_jobs}) has no such guard and calls the federation-aware
     * {@code slurm_kill_jobs()}.
     *
     * <p>The plural handler does not apply the singular path's SIGKILL/FULL_JOB
     * defaults, so {@code signal} is set to {@code SIGKILL} explicitly.
     *
     * @param jobId The Slurm job ID to cancel
     * @throws ApiException if cancellation fails
     */
    public void cancelJob(String jobId) throws ApiException {
        if (jobId == null || jobId.isEmpty()) {
            throw new IllegalArgumentException("Job ID cannot be null or empty");
        }

        LOGGER.info("Canceling Slurm job: " + jobId);

        try {
            getApiVersion();
            io.jenkins.plugins.slurm.client.model.KillJobsMsg killMsg =
                    new io.jenkins.plugins.slurm.client.model.KillJobsMsg()
                            .jobs(java.util.List.of(jobId))
                            .signal("SIGKILL");

            io.jenkins.plugins.slurm.client.model.OpenapiKillJobsResp response = api.slurmDeleteJobs(killMsg);

            if (response != null) {
                boolean hadError = false;

                if (response.getErrors() != null && !response.getErrors().isEmpty()) {
                    hadError = true;
                    LOGGER.warning("Job cancellation returned errors:");
                    for (io.jenkins.plugins.slurm.client.model.OpenapiError error : response.getErrors()) {
                        LOGGER.warning("  - " + error.getError());
                    }
                }

                if (response.getStatus() != null) {
                    for (io.jenkins.plugins.slurm.client.model.KillJobsRespJob jobResult : response.getStatus()) {
                        io.jenkins.plugins.slurm.client.model.KillJobsRespJobError jobError = jobResult.getError();
                        if (jobError != null && jobError.getCode() != null && jobError.getCode() != 0) {
                            hadError = true;
                            LOGGER.warning("Failed to signal job " + jobResult.getStepId()
                                    + ": " + jobError.getMessage()
                                    + " (code " + jobError.getCode() + ")");
                        }
                    }
                }

                if (!hadError) {
                    LOGGER.info("Job " + jobId + " canceled successfully");
                }
            }

        } catch (ApiException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Job cancellation failed with API error: HTTP " + e.getCode() + " - " + e.getMessage()
                            + " (Response: " + e.getResponseBody() + ")",
                    e);
            throw e;
        }
    }

    /**
     * List active Slurm jobs visible to the REST API user.
     */
    @NonNull
    public java.util.List<JobInfo> listJobs() throws ApiException {
        getApiVersion();
        io.jenkins.plugins.slurm.client.model.OpenapiJobInfoResp response = api.slurmGetJobs(null, null);
        if (response == null || response.getJobs() == null) {
            return java.util.List.of();
        }
        return response.getJobs();
    }

    /**
     * Get the state of a Slurm job, checking both state and exit code
     * @param jobId The Slurm job ID to check
     * @return The job state as a string (PENDING, RUNNING, COMPLETED, FAILED, etc.), or null if job not found
     * @throws ApiException if the API call fails
     */
    public String getJobState(String jobId) throws ApiException {
        SlurmJobStatus status = getJobStatus(jobId);
        return status != null ? status.getState() : null;
    }

    /**
     * Get the state, pending reason, and nodes for a Slurm job.
     *
     * @param jobId The Slurm job ID to check
     * @return job status snapshot, or {@code null} if the job is not found (404 / empty response)
     * @throws ApiException if the API call fails for reasons other than a missing job
     */
    public SlurmJobStatus getJobStatus(String jobId) throws ApiException {
        if (jobId == null || jobId.isEmpty()) {
            throw new IllegalArgumentException("Job ID cannot be null or empty");
        }
        getApiVersion();

        // Primary source: the slurmdbd single-job endpoint GET /slurmdb/<version>/job/{job_id}
        // (slurmdb_get_job). Unlike the slurmctld handlers, slurmdbd has no MAX_JOB_ID
        // guard, so it is federation-safe, and it is a single-id lookup (no "list all jobs"
        // latency). Crucially it returns the FULL accounting record — job state AND the
        // allocated node list — which the lightweight slurmctld jobs/state endpoint does not.
        // This mirrors how Slurm-web renders full job detail (incl. node) for federated ids.
        SlurmJobStatus fromAccounting = getJobStatusFromAccounting(jobId);
        if (fromAccounting != null) {
            return fromAccounting;
        }

        // Fallback: the lightweight slurmctld GET /slurm/<version>/jobs/state/ endpoint. Used when
        // slurmdbd has no record yet (brief window right after submit) or accounting is disabled.
        // It reports state only (no node), and is federation-safe.
        return getJobStatusFromStateEndpoint(jobId);
    }

    /**
     * Reads job state, reason, and allocated node(s) from the slurmdbd accounting record.
     *
     * @param jobId the Slurm job id
     * @return a status snapshot, or {@code null} if slurmdbd has no usable record (caller falls
     *     back to the slurmctld state endpoint)
     */
    @CheckForNull
    private SlurmJobStatus getJobStatusFromAccounting(String jobId) {
        try {
            OpenapiSlurmdbdJobsResp response = slurmdbApi.slurmdbGetJob(jobId);
            if (response == null
                    || response.getJobs() == null
                    || response.getJobs().isEmpty()) {
                LOGGER.fine("slurmdb has no record for job " + jobId + " yet - will fall back to state endpoint");
                return null;
            }

            Job job = selectSlurmdbJob(response.getJobs(), jobId);
            String state = resolveSlurmdbState(job);
            if (state == null) {
                LOGGER.fine("slurmdb record for job " + jobId + " has no state - will fall back to state endpoint");
                return null;
            }

            Integer exitCode = getSlurmdbExitCode(job);
            if ("COMPLETED".equals(state) && exitCode != null && exitCode != 0) {
                LOGGER.warning("Job " + jobId + " COMPLETED with non-zero exit code " + exitCode
                        + " (slurmdb) - treating as FAILED");
                state = "FAILED";
            }

            String nodes = job.getNodes();
            if (nodes != null && nodes.isBlank()) {
                nodes = null;
            }
            String reason = job.getState() != null ? job.getState().getReason() : null;

            LOGGER.info("Job " + jobId + " (slurmdb) state: " + state
                    + (exitCode != null ? " (exit code: " + exitCode + ")" : "")
                    + (nodes != null ? " on " + nodes : ""));

            return new SlurmJobStatus(jobId, state, reason, nodes);
        } catch (ApiException e) {
            LOGGER.log(
                    Level.FINE,
                    "slurmdb lookup failed for job " + jobId + " (HTTP " + e.getCode()
                            + ") - falling back to state endpoint",
                    e);
            return null;
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "slurmdb lookup error for job " + jobId + " - falling back to state endpoint", e);
            return null;
        }
    }

    /**
     * Selects the accounting record matching {@code jobId}. slurmdbd may return multiple entries
     * (heterogeneous components, or restarts sharing an id); prefer the last entry whose
     * {@code job_id} matches (most recent), falling back to the last record otherwise.
     */
    @NonNull
    static Job selectSlurmdbJob(@NonNull List<Job> jobs, @NonNull String jobId) {
        Integer requestedId = null;
        try {
            requestedId = Integer.valueOf(jobId.trim());
        } catch (NumberFormatException ignored) {
            // Non-numeric identifier (unexpected) - fall back below.
        }

        Job match = null;
        if (requestedId != null) {
            for (Job job : jobs) {
                if (requestedId.equals(job.getJobId())) {
                    match = job;
                }
            }
        }
        return match != null ? match : jobs.get(jobs.size() - 1);
    }

    /**
     * Resolves the current job state string (e.g. {@code RUNNING}) from a slurmdbd job record's
     * {@code state.current} list.
     */
    @CheckForNull
    static String resolveSlurmdbState(@CheckForNull Job job) {
        if (job == null || job.getState() == null) {
            return null;
        }
        List<JobState1.CurrentEnum> current = job.getState().getCurrent();
        if (current == null) {
            return null;
        }
        // A state this client does not know (added by a newer Slurm release) parses to the
        // sentinel. Skip it; with nothing else to go on the caller falls back to slurmctld.
        for (JobState1.CurrentEnum state : current) {
            if (state != null && state != JobState1.CurrentEnum.UNKNOWN_DEFAULT_OPEN_API) {
                return state.toString();
            }
        }
        return null;
    }

    /**
     * Extracts the process return code from a slurmdbd job record, or {@code null} if unavailable.
     */
    @CheckForNull
    private Integer getSlurmdbExitCode(@NonNull Job job) {
        try {
            ProcessExitCodeVerbose exit = job.getExitCode();
            if (exit != null
                    && exit.getReturnCode() != null
                    && exit.getReturnCode().getNumber() != null) {
                return exit.getReturnCode().getNumber();
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not extract exit code from slurmdb job record", e);
        }
        return null;
    }

    /**
     * Reads job state and pending reason from the lightweight slurmctld {@code jobs/state} endpoint.
     * Node placement is not available on this endpoint.
     */
    private SlurmJobStatus getJobStatusFromStateEndpoint(String jobId) throws ApiException {
        try {
            // Use the plural GET /slurm/<version>/jobs/state/ endpoint
            // (slurm_get_jobs_state) with server-side job_id filtering rather
            // than the singular GET /slurm/<version>/job/{job_id}. The singular handler
            // (op_handler_job) rejects any job ID >= MAX_JOB_ID (0x03FFFFFF) before
            // reaching slurmctld; federated job IDs encode the cluster ID in their top
            // bits and therefore always exceed that bound (2017 Invalid JobID). The
            // state endpoint (op_handler_job_states) has no such guard and filters by a
            // job_id list server-side, so it is federation-safe and lightweight.
            io.jenkins.plugins.slurm.client.model.OpenapiJobInfoResp response = api.slurmGetJobsState(jobId);

            if (response != null
                    && response.getJobs() != null
                    && !response.getJobs().isEmpty()) {
                io.jenkins.plugins.slurm.client.model.JobInfo jobInfo = selectJob(response.getJobs(), jobId);
                String state = resolveJobState(jobInfo);
                if (state != null) {
                    Integer exitCode = getExitCode(jobInfo);

                    LOGGER.info("Job " + jobId + " state: " + state
                            + (exitCode != null ? " (exit code: " + exitCode + ")" : ""));

                    if ("COMPLETED".equals(state) && exitCode != null && exitCode != 0) {
                        LOGGER.warning("Job " + jobId + " COMPLETED with non-zero exit code " + exitCode
                                + " - treating as FAILED");
                        state = "FAILED";
                    }

                    return new SlurmJobStatus(jobId, state, jobInfo.getStateReason(), resolveAllocatedNodes(jobInfo));
                }
            }

            LOGGER.warning("Job " + jobId + " not found in response");
            return null;

        } catch (ApiException e) {
            if (e.getCode() == 404) {
                LOGGER.info("Job " + jobId + " not found (404) - may have been cleaned up");
                return null;
            }
            LOGGER.log(
                    Level.WARNING,
                    "Failed to get job state for " + jobId + ": HTTP " + e.getCode() + " - " + e.getMessage(),
                    e);
            throw e;
        }
    }

    /**
     * Selects the job record matching the requested job ID from a filtered response.
     *
     * <p>The state endpoint filters server-side, but a federated query can return
     * multiple sibling records; prefer the entry whose {@code job_id} matches the
     * requested ID and fall back to the first record otherwise.
     */
    @NonNull
    static JobInfo selectJob(@NonNull List<JobInfo> jobs, @NonNull String jobId) {
        Integer requestedId = null;
        try {
            requestedId = Integer.valueOf(jobId.trim());
        } catch (NumberFormatException ignored) {
            // Non-numeric identifier (unexpected) - fall back to the first record.
        }

        if (requestedId != null) {
            for (JobInfo job : jobs) {
                if (requestedId.equals(job.getJobId())) {
                    return job;
                }
            }
        }
        return jobs.get(0);
    }

    /**
     * Resolves allocated compute node name(s) from a Slurm job info payload.
     * slurmrestd often omits the top-level {@code nodes} string while the job is running,
     * but still populates {@code job_resources.nodes.list} or allocation entries.
     */
    @CheckForNull
    static String resolveAllocatedNodes(@CheckForNull JobInfo jobInfo) {
        if (jobInfo == null) {
            return null;
        }

        String nodes = jobInfo.getNodes();
        if (nodes != null && !nodes.isBlank()) {
            return nodes;
        }

        JobRes resources = jobInfo.getJobResources();
        if (resources == null) {
            return null;
        }

        JobResNodes1 resourceNodes = resources.getNodes();
        if (resourceNodes == null) {
            return null;
        }

        String nodeList = resourceNodes.getList();
        if (nodeList != null && !nodeList.isBlank()) {
            return nodeList;
        }

        if (resourceNodes.getAllocation() == null
                || resourceNodes.getAllocation().isEmpty()) {
            return null;
        }

        return resourceNodes.getAllocation().stream()
                .map(JobResNode::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(","));
    }

    /**
     * Resolves Slurm job state from REST job info. When slurmrestd returns a job record
     * without {@code job_state} populated yet, treat as PENDING rather than missing.
     */
    @CheckForNull
    public static String resolveJobState(@NonNull io.jenkins.plugins.slurm.client.model.JobInfo jobInfo) {
        if (jobInfo.getJobState() != null && !jobInfo.getJobState().isEmpty()) {
            for (JobInfo.JobStateEnum state : jobInfo.getJobState()) {
                if (state != null && state != JobInfo.JobStateEnum.UNKNOWN_DEFAULT_OPEN_API) {
                    return state.toString();
                }
            }
            // Only states this client does not know (added by a newer Slurm release). The job
            // exists, so do not report it as missing or as PENDING.
            return "UNKNOWN";
        }
        LOGGER.fine("Job record present but job_state empty - treating as PENDING");
        return "PENDING";
    }

    /**
     * Extract exit code from JobInfo
     * Slurm stores exit codes in the exit_code field which contains return_code with the actual exit code number
     * @param jobInfo The job info object
     * @return The exit code, or null if not available
     */
    private Integer getExitCode(io.jenkins.plugins.slurm.client.model.JobInfo jobInfo) {
        try {
            if (jobInfo.getExitCode() != null
                    && jobInfo.getExitCode().getReturnCode() != null
                    && jobInfo.getExitCode().getReturnCode().getNumber() != null) {
                Integer exitCode = jobInfo.getExitCode().getReturnCode().getNumber();
                LOGGER.info("Job exit code extracted: " + exitCode);
                return exitCode;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not extract exit code from job info", e);
        }
        return null;
    }

    /**
     * Check if a job is in a terminal (finished) state
     * @param jobState The job state to check
     * @return true if the job is in a terminal state (COMPLETED, FAILED, CANCELLED, TIMEOUT, etc.)
     */
    public static boolean isTerminalState(String jobState) {
        if (jobState == null) {
            return false;
        }

        // Terminal states indicate the job has finished (successfully or not)
        return jobState.equals("COMPLETED")
                || jobState.equals("FAILED")
                || jobState.equals("CANCELLED")
                || jobState.equals("TIMEOUT")
                || jobState.equals("NODE_FAIL")
                || jobState.equals("BOOT_FAIL")
                || jobState.equals("DEADLINE")
                || jobState.equals("OUT_OF_MEMORY");
    }

    /**
     * Check if a job is in a failed state (not successful completion)
     * @param jobState The job state to check
     * @return true if the job failed (FAILED, CANCELLED, TIMEOUT, NODE_FAIL, etc.)
     */
    public static boolean isFailedState(String jobState) {
        if (jobState == null) {
            return false;
        }

        // Failed states indicate the job did not complete successfully
        return jobState.equals("FAILED")
                || jobState.equals("CANCELLED")
                || jobState.equals("TIMEOUT")
                || jobState.equals("NODE_FAIL")
                || jobState.equals("BOOT_FAIL")
                || jobState.equals("DEADLINE")
                || jobState.equals("OUT_OF_MEMORY");
    }
}
