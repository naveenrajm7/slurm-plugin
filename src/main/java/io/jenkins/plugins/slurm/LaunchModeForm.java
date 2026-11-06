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
        if (formData == null) {
            return;
        }
        flattenChoice(formData, "launchMode", NESTED_BLOCKS);
        // Connection mode is its own radio, outside the launch-mode blocks, and has no nested
        // properties. The form still posts an object when the control is a radioBlock.
        flattenChoice(formData, "agentConnection", List.of());
    }

    private static void flattenChoice(JSONObject formData, String field, List<String> nestedBlocks) {
        if (!formData.has(field)) {
            return;
        }
        Object raw = formData.get(field);
        if (!(raw instanceof JSONObject choice)) {
            return;
        }
        formData.put(field, choice.optString("value", ""));
        for (String block : nestedBlocks) {
            if (choice.has(block)) {
                formData.put(block, choice.get(block));
            }
        }
    }
}
