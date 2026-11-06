package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;

class LaunchModeFormTest {

    @Test
    void flatten_promotesSelectedRadioAndNestedBlock() {
        JSONObject agent = new JSONObject();
        agent.put("javaPath", "/opt/java/bin/java");

        JSONObject mode = new JSONObject();
        mode.put("value", "NATIVE");
        mode.put("agent", agent);

        JSONObject form = new JSONObject();
        form.put("name", "my-slurm");
        form.put("launchMode", mode);

        LaunchModeForm.flatten(form);

        assertEquals("NATIVE", form.getString("launchMode"));
        assertEquals("/opt/java/bin/java", form.getJSONObject("agent").getString("javaPath"));
        assertFalse(form.getJSONObject("agent").has("value"));
    }

    @Test
    void flatten_inheritRadioBecomesTheString() {
        JSONObject mode = new JSONObject();
        mode.put("value", "INHERIT");
        JSONObject form = new JSONObject();
        form.put("launchMode", mode);

        LaunchModeForm.flatten(form);

        assertEquals("INHERIT", form.getString("launchMode"));
        assertFalse(form.has("agent"));
        assertFalse(form.has("pyxis"));
        assertFalse(form.has("vmocs"));
    }

    @Test
    void flatten_leavesStringLaunchModeUntouched() {
        JSONObject vmocs = new JSONObject();
        vmocs.put("vmImage", "base-ubuntu");
        JSONObject form = new JSONObject();
        form.put("launchMode", "VMOCS");
        form.put("vmocs", vmocs);

        LaunchModeForm.flatten(form);

        assertEquals("VMOCS", form.getString("launchMode"));
        assertEquals("base-ubuntu", form.getJSONObject("vmocs").getString("vmImage"));
    }

    @Test
    void flatten_promotesAgentConnectionWithoutTouchingLaunchMode() {
        JSONObject connection = new JSONObject();
        connection.put("value", "JNLP");
        JSONObject mode = new JSONObject();
        mode.put("value", "PYXIS");
        JSONObject form = new JSONObject();
        form.put("launchMode", mode);
        form.put("agentConnection", connection);

        LaunchModeForm.flatten(form);

        assertEquals("PYXIS", form.getString("launchMode"));
        assertEquals("JNLP", form.getString("agentConnection"));
    }
}
