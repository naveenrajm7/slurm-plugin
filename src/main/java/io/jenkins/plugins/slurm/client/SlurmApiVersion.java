package io.jenkins.plugins.slurm.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Slurm REST API versions this plugin can talk to, newest first.
 *
 * <p>slurmrestd puts the version in every path ({@code /slurm/v0.0.42/jobs/}). Each Slurm release
 * serves several versions and removes the oldest, so one cluster upgrade can make a version
 * disappear. The client is generated once, from {@link #REFERENCE}, and the version segment is
 * replaced per request. {@code SlurmApiCompatibilityTest} checks that the operations and fields
 * the plugin uses have the same shape in every spec under {@code src/main/openapi}.
 */
public enum SlurmApiVersion {
    V0_0_45("v0.0.45", "26.05", "28.05"),
    V0_0_44("v0.0.44", "25.11", "27.11"),
    V0_0_43("v0.0.43", "25.05", "27.05"),
    V0_0_42("v0.0.42", "24.11", "26.11");

    /** The version whose spec the client is generated from, and so the one in the generated paths. */
    public static final SlurmApiVersion REFERENCE = V0_0_42;

    private static final Pattern VERSION_SEGMENT = Pattern.compile("/(slurmdb|slurm)/v\\d+\\.\\d+\\.\\d+/");

    private final String wireName;
    private final String addedIn;
    private final String removedIn;

    SlurmApiVersion(String wireName, String addedIn, String removedIn) {
        this.wireName = wireName;
        this.addedIn = addedIn;
        this.removedIn = removedIn;
    }

    /** The path segment, for example {@code v0.0.42}. */
    @NonNull
    public String getWireName() {
        return wireName;
    }

    /** First Slurm release that serves this version. */
    @NonNull
    public String getAddedIn() {
        return addedIn;
    }

    /** First Slurm release that no longer serves this version. */
    @NonNull
    public String getRemovedIn() {
        return removedIn;
    }

    /** Versions in the order detection tries them: newest first. */
    @NonNull
    public static List<SlurmApiVersion> newestFirst() {
        return List.of(values());
    }

    /**
     * @return the version with this path segment, or {@code null} for blank or unknown input
     */
    @CheckForNull
    public static SlurmApiVersion fromWireName(@CheckForNull String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return null;
        }
        String trimmed = wireName.trim();
        for (SlurmApiVersion version : values()) {
            if (version.wireName.equals(trimmed)) {
                return version;
            }
        }
        return null;
    }

    /** All supported path segments, newest first, for messages. */
    @NonNull
    public static String supportedWireNames() {
        return Stream.of(values()).map(SlurmApiVersion::getWireName).collect(Collectors.joining(", "));
    }

    /** {@code /slurm/<this version>/ping/} under the given base URL (no trailing slash). */
    @NonNull
    URI pingUri(@NonNull String baseUrl) {
        return URI.create(baseUrl + "/slurm/" + wireName + "/ping/");
    }

    /** Replaces the version segment of a generated request URI with this version. */
    @NonNull
    URI rewrite(@NonNull URI generated) {
        String rewritten = VERSION_SEGMENT.matcher(generated.toString()).replaceFirst("/$1/" + wireName + "/");
        return URI.create(rewritten);
    }

    @Override
    public String toString() {
        return wireName;
    }
}
