package io.jenkins.plugins.slurm;

import java.util.List;
import net.sf.json.JSONObject;

/**
 * Normalizes the launch-mode control posted by {@code f:radioBlock}.
 *
 * <p>The radio posts an object ({@code {"value":"NATIVE","agent":{...}}}) because the selected
 * block nests {@code f:property}. {@code setLaunchMode} expects the mode string, and the nested
 * agent, Pyxis, or VMoCS block must sit at the top of the form for its setter to see it. JCasC and
 * other callers that already pass a string are left unchanged.
 */
final class LaunchModeForm {

    private static final List<String> NESTED_BLOCKS = List.of("agent", "pyxis", "vmocs");

    private LaunchModeForm() {}

    static void flatten(JSONObject formData) {
        if (formData == null || !formData.has("launchMode")) {
            return;
        }
        Object raw = formData.get("launchMode");
        if (!(raw instanceof JSONObject mode)) {
            return;
        }
        formData.put("launchMode", mode.optString("value", ""));
        for (String block : NESTED_BLOCKS) {
            if (mode.has(block)) {
                formData.put(block, mode.get(block));
            }
        }
    }
}
