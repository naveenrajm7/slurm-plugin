package io.jenkins.plugins.slurm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The client is generated from one spec and used against several REST API versions. This test is
 * what makes that safe: for every spec under {@code src/main/openapi} it checks that the
 * operations the plugin calls exist, and that the fields it reads or writes have the same shape
 * as in the reference spec.
 *
 * <p>To add a version: put its spec in {@code src/main/openapi} as {@code slurm-<version>.json},
 * add the constant to {@link SlurmApiVersion}, and run this test.
 */
class SlurmApiCompatibilityTest {

    private static final Path SPEC_DIR = Path.of("src", "main", "openapi");
    private static final Path CLIENT_SPEC = SPEC_DIR.resolve("client").resolve("slurm-client.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Path below {@code /<api>/<version>}, and the HTTP method the plugin uses on it. */
    private static final String[][] OPERATIONS = {
        {"slurm", "/ping/", "get"},
        {"slurm", "/job/submit", "post"},
        {"slurm", "/jobs/", "get"},
        {"slurm", "/jobs/", "delete"},
        {"slurm", "/jobs/state/", "get"},
        {"slurmdb", "/job/{job_id}", "get"},
    };

    /** Schema name without its version prefix, and the properties the plugin reads or writes. */
    private static final Map<String, List<String>> FIELDS = fields();

    private static Map<String, List<String>> fields() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        // Written by SlurmJobBuilder.
        fields.put(
                "job_desc_msg",
                List.of(
                        "name",
                        "partition",
                        "current_working_directory",
                        "cpus_per_task",
                        "memory_per_node",
                        "memory_per_cpu",
                        "time_limit",
                        "time_minimum",
                        "minimum_nodes",
                        "maximum_nodes",
                        "tasks",
                        "tres_per_job",
                        "tres_per_node",
                        "tres_per_task",
                        "tres_per_socket",
                        "tres_bind",
                        "tres_freq",
                        "account",
                        "qos",
                        "constraints",
                        "required_nodes",
                        "excluded_nodes",
                        "comment",
                        "prefer",
                        "reservation",
                        "network",
                        "nodes",
                        "standard_output",
                        "standard_error",
                        "standard_input",
                        "nice",
                        "reboot",
                        "minimum_cpus",
                        "maximum_cpus",
                        "sockets_per_node",
                        "threads_per_core",
                        "tasks_per_node",
                        "tasks_per_socket",
                        "tasks_per_core",
                        "tasks_per_board",
                        "ntasks_per_tres",
                        "minimum_cpus_per_node",
                        "minimum_boards_per_node",
                        "minimum_sockets_per_board",
                        "temporary_disk_per_node",
                        "environment",
                        "script"));
        fields.put("uint32_no_val_struct", List.of("set", "infinite", "number"));
        fields.put("uint64_no_val_struct", List.of("set", "infinite", "number"));
        fields.put("job_submit_req", List.of("job"));
        fields.put("openapi_job_submit_response", List.of("job_id", "errors"));
        fields.put("openapi_error", List.of("error"));
        // Cancel.
        fields.put("kill_jobs_msg", List.of("jobs", "signal"));
        fields.put("openapi_kill_jobs_resp", List.of("status", "errors"));
        fields.put("kill_jobs_resp_job", List.of("error", "step_id"));
        // Status from slurmctld.
        fields.put("openapi_job_info_resp", List.of("jobs"));
        fields.put(
                "job_info",
                List.of("job_id", "name", "job_state", "state_reason", "nodes", "job_resources", "exit_code"));
        fields.put("job_res", List.of("nodes"));
        fields.put("process_exit_code_verbose", List.of("return_code"));
        // Status from slurmdbd.
        fields.put("openapi_slurmdbd_jobs_resp", List.of("jobs"));
        fields.put("job", List.of("job_id", "state", "nodes", "exit_code"));
        // Ping.
        fields.put("openapi_ping_array_resp", List.of("pings", "meta"));
        fields.put("controller_ping", List.of("hostname", "pinged", "responding", "latency", "mode", "primary"));
        return fields;
    }

    /**
     * Response fields a version may lack. The plugin only shows them in Test Connection and
     * leaves the line out when the value is absent. {@code pinged} and {@code mode} were
     * deprecated and are gone from v0.0.45.
     */
    private static final Set<String> DISPLAY_ONLY = Set.of("controller_ping.pinged", "controller_ping.mode");

    private static List<Path> specFiles() throws IOException {
        try (Stream<Path> files = Files.list(SPEC_DIR)) {
            return files.filter(p -> p.getFileName().toString().matches("slurm-v\\d+\\.\\d+\\.\\d+\\.json"))
                    .sorted()
                    .toList();
        }
    }

    private static String versionOf(Path spec) {
        String file = spec.getFileName().toString();
        return file.substring("slurm-".length(), file.length() - ".json".length());
    }

    private static Path specFor(SlurmApiVersion version) {
        return SPEC_DIR.resolve("slurm-" + version.getWireName() + ".json");
    }

    @Test
    void referenceSpecIsPresent() {
        assertTrue(Files.isRegularFile(specFor(SlurmApiVersion.REFERENCE)), specFor(SlurmApiVersion.REFERENCE) + "");
    }

    @Test
    void everySpecBelongsToASupportedVersion() throws IOException {
        for (Path spec : specFiles()) {
            assertNotNull(
                    SlurmApiVersion.fromWireName(versionOf(spec)),
                    spec + " has no SlurmApiVersion constant; add one or remove the spec");
        }
    }

    /**
     * Reported as skipped, not failed, while a supported version has no spec in the repository:
     * that version is then enabled on the strength of Slurm keeping these endpoints stable, not on
     * a check. Add the spec to turn this into a pass.
     */
    @Test
    void everySupportedVersionHasASpec() {
        List<String> missing = new ArrayList<>();
        for (SlurmApiVersion version : SlurmApiVersion.values()) {
            if (!Files.isRegularFile(specFor(version))) {
                missing.add(version.getWireName());
            }
        }
        assumeTrue(missing.isEmpty(), "No spec checked in for " + missing + "; those versions are not verified");
    }

    @Test
    void everySpecHasTheOperationsThePluginCalls() throws IOException {
        for (Path spec : specFiles()) {
            String version = versionOf(spec);
            JsonNode paths = MAPPER.readTree(spec.toFile()).path("paths");
            for (String[] operation : OPERATIONS) {
                String path = "/" + operation[0] + "/" + version + operation[1];
                assertTrue(
                        paths.path(path).has(operation[2]),
                        spec.getFileName() + " has no " + operation[2].toUpperCase() + " " + path);
            }
        }
    }

    @Test
    void fieldsThePluginUsesHaveTheReferenceShapeInEverySpec() throws IOException {
        JsonNode reference = MAPPER.readTree(specFor(SlurmApiVersion.REFERENCE).toFile());
        String referenceVersion = SlurmApiVersion.REFERENCE.getWireName();

        List<String> problems = new ArrayList<>();
        for (Path spec : specFiles()) {
            String version = versionOf(spec);
            JsonNode candidate = MAPPER.readTree(spec.toFile());
            for (Map.Entry<String, List<String>> entry : FIELDS.entrySet()) {
                for (String field : entry.getValue()) {
                    JsonNode expected = property(reference, referenceVersion, entry.getKey(), field);
                    JsonNode actual = property(candidate, version, entry.getKey(), field);
                    String where = spec.getFileName() + " " + entry.getKey() + "." + field;
                    if (expected.isMissingNode()) {
                        problems.add("reference spec has no " + entry.getKey() + "." + field);
                    } else if (actual.isMissingNode()) {
                        if (!DISPLAY_ONLY.contains(entry.getKey() + "." + field)) {
                            problems.add(where + " is missing");
                        }
                    } else if (!shape(reference, expected).equals(shape(candidate, actual))) {
                        problems.add(where + " is " + shape(candidate, actual) + ", reference has "
                                + shape(reference, expected));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static JsonNode property(JsonNode spec, String version, String schema, String field) {
        return spec.path("components")
                .path("schemas")
                .path(version + "_" + schema)
                .path("properties")
                .path(field);
    }

    /**
     * What decides whether the generated type still fits: type, format and, for objects, the
     * referenced schema. A reference to a schema that is not an object (a named array or enum) is
     * followed, because slurmrestd writes the same field inline or as a reference depending on how
     * the spec was generated.
     */
    private static String shape(JsonNode spec, JsonNode node) {
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            JsonNode target = spec.path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
            if (!target.isMissingNode() && !"object".equals(target.path("type").asText())) {
                return shape(spec, target);
            }
            return stripVersion(ref);
        }
        StringBuilder shape = new StringBuilder(node.path("type").asText("?"));
        if (node.has("format")) {
            shape.append('/').append(node.get("format").asText());
        }
        if (node.has("items")) {
            shape.append(" of ").append(shape(spec, node.get("items")));
        }
        return shape.toString();
    }

    private static String stripVersion(String ref) {
        return ref.replaceFirst("(#/components/schemas/)v\\d+\\.\\d+\\.\\d+_", "$1");
    }

    /**
     * The generator reads the committed {@code client/slurm-client.json}, not the reference spec.
     * This repeats what {@code scripts/preprocess-openapi-spec.py} does and fails when the two
     * drift apart, for example after the reference spec is replaced without rerunning the script.
     */
    @Test
    void clientSpecIsTheReferenceSpecWithVersionPrefixesRemoved() throws IOException {
        JsonNode expected = stripVersionPrefixes(
                MAPPER.readTree(specFor(SlurmApiVersion.REFERENCE).toFile()));
        JsonNode actual = MAPPER.readTree(CLIENT_SPEC.toFile());
        assertEquals(
                expected,
                actual,
                CLIENT_SPEC + " is out of date. Run: python scripts/preprocess-openapi-spec.py "
                        + specFor(SlurmApiVersion.REFERENCE) + " " + CLIENT_SPEC);
    }

    private static JsonNode stripVersionPrefixes(JsonNode spec) {
        JsonNode result = rewriteRefs(spec);
        ObjectNode schemas = (ObjectNode) result.path("components").path("schemas");
        ObjectNode renamed = MAPPER.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = schemas.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> schema = it.next();
            renamed.set(schema.getKey().replaceFirst("^v0\\.0\\.\\d+_", ""), schema.getValue());
        }
        ((ObjectNode) result.path("components")).set("schemas", renamed);
        for (JsonNode pathItem : result.path("paths")) {
            for (JsonNode operation : pathItem) {
                if (operation.has("operationId")) {
                    String id = operation.get("operationId").asText();
                    ((ObjectNode) operation).put("operationId", id.replaceAll("_v\\d{4}_", "_"));
                }
            }
        }
        return result;
    }

    private static JsonNode rewriteRefs(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                if (field.getKey().equals("$ref") && field.getValue().isTextual()) {
                    copy.set(
                            "$ref",
                            TextNode.valueOf(stripVersion(field.getValue().asText())));
                } else {
                    copy.set(field.getKey(), rewriteRefs(field.getValue()));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            node.forEach(item -> copy.add(rewriteRefs(item)));
            return copy;
        }
        return node;
    }
}
