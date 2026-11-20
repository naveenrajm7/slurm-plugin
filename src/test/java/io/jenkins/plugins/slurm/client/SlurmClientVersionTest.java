package io.jenkins.plugins.slurm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jenkins.plugins.slurm.client.model.JobDescMsg;
import io.jenkins.plugins.slurm.client.model.JobSubmitReq;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link SlurmClient} against a local HTTP server that behaves like slurmrestd for the
 * endpoints the plugin uses: it answers only on the REST API versions it is told to serve and
 * returns 404 for the rest.
 */
class SlurmClientVersionTest {

    private static final Pattern VERSIONED_PATH = Pattern.compile("^/(slurm|slurmdb)/(v\\d+\\.\\d+\\.\\d+)(/.*)$");
    private static final String TOKEN = "test-token";

    private HttpServer server;
    private String baseUrl;

    private final Set<SlurmApiVersion> served = EnumSet.noneOf(SlurmApiVersion.class);
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();

    /** When set, every request gets this status, as slurmrestd does for a rejected token. */
    private volatile int forcedStatus;

    /** Body returned by {@code GET /slurm/<version>/jobs/state/}. */
    private volatile String jobStateBody = "{\"jobs\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(method + " " + path);
        String token = exchange.getRequestHeaders().getFirst("X-Slurm-USER-TOKEN");
        tokens.add(token == null ? "" : token);
        exchange.getRequestBody().readAllBytes();

        if (forcedStatus != 0) {
            respond(exchange, forcedStatus, "{\"errors\":[{\"error\":\"forced\"}]}");
            return;
        }

        Matcher matcher = VERSIONED_PATH.matcher(path);
        SlurmApiVersion version = matcher.matches() ? SlurmApiVersion.fromWireName(matcher.group(2)) : null;
        if (version == null || !served.contains(version)) {
            respond(exchange, 404, "{\"errors\":[{\"error\":\"Unable to find requested URL\"}]}");
            return;
        }

        String route = method + " /" + matcher.group(1) + matcher.group(3);
        String body;
        switch (route) {
            case "GET /slurm/ping/":
                body = "{\"pings\":[{\"hostname\":\"ctl01\",\"pinged\":\"UP\",\"responding\":true,"
                        + "\"latency\":120,\"mode\":\"primary\",\"primary\":true}],"
                        + "\"meta\":{\"slurm\":{\"release\":\"25.11.0\",\"cluster\":\"cluster-a\"}}}";
                break;
            case "POST /slurm/job/submit":
                body = "{\"job_id\":4242}";
                break;
            case "DELETE /slurm/jobs/":
                body = "{\"status\":[]}";
                break;
            case "GET /slurm/jobs/":
                body = "{\"jobs\":[{\"job_id\":7,\"name\":\"listed\"}]}";
                break;
            case "GET /slurm/jobs/state/":
                body = jobStateBody;
                break;
            case "GET /slurmdb/job/7":
                body = "{\"jobs\":[]}";
                break;
            default:
                respond(exchange, 404, "{\"errors\":[{\"error\":\"no such route in test server: " + route + "\"}]}");
                return;
        }
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private SlurmClient client() throws Exception {
        return new SlurmClient(baseUrl, TOKEN);
    }

    private static List<String> pings(SlurmApiVersion... versions) {
        return java.util.Arrays.stream(versions)
                .map(v -> "GET /slurm/" + v.getWireName() + "/ping/")
                .toList();
    }

    @Test
    void detectionTakesTheNewestVersionTheServerServes() throws Exception {
        served.addAll(EnumSet.of(SlurmApiVersion.V0_0_42, SlurmApiVersion.V0_0_43, SlurmApiVersion.V0_0_44));
        SlurmClient client = client();

        assertEquals(SlurmApiVersion.V0_0_44, client.getApiVersion());
        assertFalse(client.isApiVersionConfigured());
        assertEquals(pings(SlurmApiVersion.V0_0_45, SlurmApiVersion.V0_0_44), requests);
        assertEquals(List.of(TOKEN, TOKEN), tokens, "detection must authenticate like every other request");
    }

    @Test
    void detectionFallsBackToTheOldestSupportedVersion() throws Exception {
        served.add(SlurmApiVersion.V0_0_42);
        SlurmClient client = client();

        assertEquals(SlurmApiVersion.V0_0_42, client.getApiVersion());
        assertEquals(
                pings(
                        SlurmApiVersion.V0_0_45,
                        SlurmApiVersion.V0_0_44,
                        SlurmApiVersion.V0_0_43,
                        SlurmApiVersion.V0_0_42),
                requests);
    }

    @Test
    void detectionRunsOncePerClient() throws Exception {
        served.add(SlurmApiVersion.V0_0_45);
        SlurmClient client = client();

        client.listJobs();
        client.listJobs();

        assertEquals(
                List.of("GET /slurm/v0.0.45/ping/", "GET /slurm/v0.0.45/jobs/", "GET /slurm/v0.0.45/jobs/"), requests);
    }

    @Test
    void constructorDoesNotContactTheServer() throws Exception {
        served.add(SlurmApiVersion.V0_0_45);
        client();
        assertTrue(requests.isEmpty());
    }

    @Test
    void configuredVersionIsUsedWithoutDetection() throws Exception {
        served.addAll(EnumSet.allOf(SlurmApiVersion.class));
        SlurmClient client = new SlurmClient(baseUrl, TOKEN, SlurmApiVersion.V0_0_43);

        assertTrue(client.isApiVersionConfigured());
        assertEquals(1, client.listJobs().size());
        assertEquals(List.of("GET /slurm/v0.0.43/jobs/"), requests);
    }

    @Test
    void everyCallUsesTheSelectedVersionOnBothApis() throws Exception {
        for (SlurmApiVersion version : SlurmApiVersion.values()) {
            requests.clear();
            served.clear();
            served.add(version);
            jobStateBody = "{\"jobs\":[{\"job_id\":7,\"job_state\":[\"RUNNING\"]}]}";
            SlurmClient client = new SlurmClient(baseUrl, TOKEN, version);
            String v = version.getWireName();

            SlurmPingInfo ping = client.getSlurmInfo();
            assertNotNull(ping, "ping on " + v);
            assertEquals("25.11.0", ping.getVersion());
            assertEquals(
                    Integer.valueOf(4242),
                    client.submitJob(new JobSubmitReq()
                                    .job(new JobDescMsg().name("agent").script("#!/bin/sh\n")))
                            .getJobId());
            client.cancelJob("7");
            assertEquals(1, client.listJobs().size());
            SlurmJobStatus status = client.getJobStatus("7");
            assertNotNull(status);
            assertEquals("RUNNING", status.getState());

            assertEquals(
                    List.of(
                            "GET /slurm/" + v + "/ping/",
                            "POST /slurm/" + v + "/job/submit",
                            "DELETE /slurm/" + v + "/jobs/",
                            "GET /slurm/" + v + "/jobs/",
                            "GET /slurmdb/" + v + "/job/7",
                            "GET /slurm/" + v + "/jobs/state/"),
                    requests);
        }
    }

    @Test
    void serverWithNoSupportedVersionIsReportedAndRetried() throws Exception {
        SlurmClient client = client();

        ApiException first = assertThrows(ApiException.class, client::listJobs);
        assertEquals(SlurmClient.NO_SUPPORTED_VERSION_STATUS, first.getCode());
        assertTrue(first.getMessage().contains("v0.0.45, v0.0.44, v0.0.43, v0.0.42"), first.getMessage());
        assertEquals(4, requests.size());

        // The failure is not remembered: once the server serves a version, the same client works.
        served.add(SlurmApiVersion.V0_0_44);
        assertEquals(SlurmApiVersion.V0_0_44, client.getApiVersion());
    }

    @Test
    void rejectedTokenStopsDetectionAndIsNotMistakenForAMissingVersion() throws Exception {
        served.addAll(EnumSet.allOf(SlurmApiVersion.class));
        forcedStatus = 401;
        SlurmClient client = client();

        ApiException error = assertThrows(ApiException.class, client::getApiVersion);
        assertEquals(401, error.getCode());
        assertEquals(pings(SlurmApiVersion.V0_0_45), requests);
        assertNull(client.getSlurmInfo(), "ping keeps reporting failure as null");

        forcedStatus = 0;
        assertEquals(SlurmApiVersion.V0_0_45, client.getApiVersion());
    }

    @Test
    void unreachableServerIsATransportError() throws Exception {
        SlurmClient client = client();
        server.stop(0);

        ApiException error = assertThrows(ApiException.class, client::getApiVersion);
        assertEquals(0, error.getCode());
        assertTrue(error.getCause() instanceof IOException, String.valueOf(error.getCause()));
    }

    @Test
    void valuesAndFieldsFromANewerServerDoNotFailTheResponse() throws Exception {
        served.add(SlurmApiVersion.V0_0_45);
        SlurmClient client = client();

        jobStateBody = "{\"jobs\":[{\"job_id\":7,\"job_state\":[\"RUNNING\",\"STATE_FROM_THE_FUTURE\"],"
                + "\"field_from_the_future\":{\"x\":1}}],\"another_new_field\":true}";
        assertEquals("RUNNING", client.getJobStatus("7").getState());

        jobStateBody = "{\"jobs\":[{\"job_id\":7,\"job_state\":[\"STATE_FROM_THE_FUTURE\"]}]}";
        SlurmJobStatus unknown = client.getJobStatus("7");
        assertNotNull(unknown, "a job in a state this client does not know still exists");
        assertEquals("UNKNOWN", unknown.getState());
        assertFalse(SlurmClient.isTerminalState(unknown.getState()));
    }

    @Test
    void rewriteReplacesOnlyTheVersionSegment() {
        assertEquals(
                URI.create("http://host:6820/slurm/v0.0.45/jobs/state/?job_id=7"),
                SlurmApiVersion.V0_0_45.rewrite(URI.create("http://host:6820/slurm/v0.0.42/jobs/state/?job_id=7")));
        assertEquals(
                URI.create("https://host/proxy/slurmdb/v0.0.43/job/7"),
                SlurmApiVersion.V0_0_43.rewrite(URI.create("https://host/proxy/slurmdb/v0.0.42/job/7")));
    }

    @Test
    void fromWireNameAcceptsOnlySupportedVersions() {
        assertEquals(SlurmApiVersion.V0_0_44, SlurmApiVersion.fromWireName(" v0.0.44 "));
        assertNull(SlurmApiVersion.fromWireName("v0.0.41"));
        assertNull(SlurmApiVersion.fromWireName(""));
        assertNull(SlurmApiVersion.fromWireName(null));
        assertEquals(SlurmApiVersion.V0_0_45, SlurmApiVersion.newestFirst().get(0));
    }
}
