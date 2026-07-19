package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.slurm.client.model.JobDescMsg;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SlurmJobBuilder} script generation.
 */
public class SlurmJobBuilderTest {

    private static final String JENKINS_URL = "http://jenkins:8080/jenkins/";
    private static final String AGENT_NAME = "test-agent-1";
    private static final String SECRET = "abc123";

    @Test
    public void testPyxisScriptUsesContainerPaths() {
        SlurmJobTemplate template = baseTemplate();
        PyxisConfig pyxis = new PyxisConfig();
        pyxis.setContainerImage("/path/to/image.sqsh");
        template.setPyxis(pyxis);
        // launchMode inferred as PYXIS because pyxis is configured (backwards-compat)

        String script = buildScript(template);

        assertTrue(script.contains("--container-image=/path/to/image.sqsh"));
        assertTrue(script.contains(AgentLaunchConfig.CONTAINER_JAVA_PATH));
        assertTrue(script.contains(AgentLaunchConfig.CONTAINER_JAR_PATH));
        assertTrue(script.contains("-webSocket"));
    }

    @Test
    public void testNativeScriptUsesConfiguredPaths() {
        SlurmJobTemplate template = baseTemplate();
        AgentLaunchConfig agent = new AgentLaunchConfig();
        agent.setJavaPath("/usr/bin/java");
        agent.setJarPath("/opt/jenkins/agent.jar");
        template.setAgent(agent);

        String script = buildScript(template);

        assertTrue(script.contains("srun -N1 -n1 /usr/bin/java -jar '/opt/jenkins/agent.jar'"));
        assertTrue(script.contains("-url " + JENKINS_URL));
        assertTrue(script.contains("-secret " + SECRET));
        assertFalse(script.contains("--container-image"));
    }

    @Test
    public void testNativeDownloadJarScript() {
        SlurmJobTemplate template = baseTemplate();
        AgentLaunchConfig agent = new AgentLaunchConfig();
        agent.setDownloadJar(true);
        template.setAgent(agent);

        String script = buildScript(template);

        assertTrue(script.contains("AGENT_JAR='/tmp/jenkins/agent.jar'"));
        assertTrue(script.contains("jnlpJars/agent.jar"));
        assertTrue(script.contains("java -jar"));
        assertTrue(script.contains("$AGENT_JAR"));
    }

    @Test
    public void testNativeSetupScript() {
        SlurmJobTemplate template = baseTemplate();
        AgentLaunchConfig agent = new AgentLaunchConfig();
        agent.setJarPath("/opt/jenkins/agent.jar");
        agent.setSetupScript("module load java/21\n# comment\nexport FOO=bar");
        template.setAgent(agent);

        String script = buildScript(template);

        assertTrue(script.contains("module load java/21"));
        assertTrue(script.contains("export FOO=bar"));
        assertFalse(script.contains("# comment"));
    }

    @Test
    public void testCloudDefaultsUsedWhenTemplateHasNoAgent() {
        SlurmJobTemplate template = baseTemplate();

        AgentLaunchConfig cloudAgent = new AgentLaunchConfig();
        cloudAgent.setJavaPath("/opt/jenkins/jdk-17/bin/java");
        cloudAgent.setJarPath("/opt/jenkins/agent.jar");

        SlurmJobBuilder builder = new SlurmJobBuilder(template, AGENT_NAME, JENKINS_URL, SECRET, cloudAgent);
        String script = builder.build().getScript();

        assertTrue(script.contains("/opt/jenkins/jdk-17/bin/java"));
        assertTrue(script.contains("'/opt/jenkins/agent.jar'"));
    }

    @Test
    public void testMissingLaunchConfigFails() {
        SlurmJobTemplate template = baseTemplate();

        SlurmJobBuilder builder = new SlurmJobBuilder(template, AGENT_NAME, JENKINS_URL, SECRET);
        assertThrows(IllegalStateException.class, builder::build);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests for explicit launchMode field
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * When {@code launchMode} is explicitly set to {@code "PYXIS"} the builder must
     * generate a Pyxis/Enroot script, regardless of whether an AgentLaunchConfig is
     * also present on the template.
     */
    @Nested
    class LaunchModeExplicitTest {

        @Test
        void explicitPyxisMode_generatesPyxisScript() {
            SlurmJobTemplate template = baseTemplate();
            template.setLaunchMode("PYXIS");
            PyxisConfig pyxis = new PyxisConfig();
            pyxis.setContainerImage("/images/myapp.sqsh");
            template.setPyxis(pyxis);

            String script = buildScript(template);

            assertTrue(script.contains("--container-image=/images/myapp.sqsh"));
            assertTrue(script.contains(AgentLaunchConfig.CONTAINER_JAVA_PATH));
            assertTrue(script.contains(AgentLaunchConfig.CONTAINER_JAR_PATH));
        }

        @Test
        void explicitNativeMode_generatesNativeScript() {
            SlurmJobTemplate template = baseTemplate();
            template.setLaunchMode("NATIVE");
            AgentLaunchConfig agent = new AgentLaunchConfig();
            agent.setJarPath("/opt/jenkins/agent.jar");
            template.setAgent(agent);

            String script = buildScript(template);

            assertFalse(script.contains("--container-image"));
            assertTrue(script.contains("-jar '/opt/jenkins/agent.jar'"));
        }

        @Test
        void nativeModeWithPyxisAlsoPresent_doesNotUsePyxis() {
            // When the user has launchMode=NATIVE but an old pyxis config is still
            // stored (e.g. after switching modes in the UI), the builder must honour
            // the explicit mode and NOT use the Pyxis config.
            SlurmJobTemplate template = baseTemplate();
            template.setLaunchMode("NATIVE");

            PyxisConfig pyxis = new PyxisConfig();
            pyxis.setContainerImage("/images/myapp.sqsh");
            template.setPyxis(pyxis);

            AgentLaunchConfig agent = new AgentLaunchConfig();
            agent.setJarPath("/opt/jenkins/agent.jar");
            template.setAgent(agent);

            String script = buildScript(template);

            assertFalse(script.contains("--container-image"));
            assertTrue(script.contains("-jar '/opt/jenkins/agent.jar'"));
        }

        @Test
        void pyxisModeWithoutContainerImage_throwsIllegalState() {
            SlurmJobTemplate template = baseTemplate();
            template.setLaunchMode("PYXIS");
            // No PyxisConfig set → must fail with a clear message.

            SlurmJobBuilder builder = new SlurmJobBuilder(template, AGENT_NAME, JENKINS_URL, SECRET);
            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        void pyxisModeWithEmptyContainerImage_throwsIllegalState() {
            SlurmJobTemplate template = baseTemplate();
            template.setLaunchMode("PYXIS");
            PyxisConfig pyxis = new PyxisConfig(); // containerImage is "" by default
            template.setPyxis(pyxis);

            SlurmJobBuilder builder = new SlurmJobBuilder(template, AGENT_NAME, JENKINS_URL, SECRET);
            assertThrows(IllegalStateException.class, builder::build);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests for launchMode inference (backwards-compatibility)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class LaunchModeInferenceTest {

        @Test
        void noConfigAtAll_defaultsToNativeMode() {
            SlurmJobTemplate t = new SlurmJobTemplate();
            // launchMode field is null → must infer NATIVE (pyxis is null)
            assertTrue(t.isNativeLaunch());
            assertFalse(t.isPyxisLaunch());
        }

        @Test
        void pyxisConfigured_noExplicitMode_infersPyxis() {
            SlurmJobTemplate t = new SlurmJobTemplate();
            PyxisConfig pyxis = new PyxisConfig();
            pyxis.setContainerImage("/path/to/img.sqsh");
            t.setPyxis(pyxis);
            // Legacy template without launchMode set → infer from pyxis presence.
            assertTrue(t.isPyxisLaunch());
            assertFalse(t.isNativeLaunch());
        }

        @Test
        void explicitNativeMode_overridesInference_evenWhenPyxisPresent() {
            SlurmJobTemplate t = new SlurmJobTemplate();
            PyxisConfig pyxis = new PyxisConfig();
            pyxis.setContainerImage("/path/to/img.sqsh");
            t.setPyxis(pyxis);
            t.setLaunchMode("NATIVE");

            assertTrue(t.isNativeLaunch());
            assertFalse(t.isPyxisLaunch());
        }

        @Test
        void setLaunchMode_unknownValue_defaultsToNative() {
            SlurmJobTemplate t = new SlurmJobTemplate();
            t.setLaunchMode("UNKNOWN_VALUE");
            assertTrue(t.isNativeLaunch());
        }
    }

    private static SlurmJobTemplate baseTemplate() {
        SlurmJobTemplate template = new SlurmJobTemplate("native", "linux");
        template.setPartition("compute");
        template.setCurrentWorkingDirectory("/tmp/jenkins");
        return template;
    }

    private static String buildScript(SlurmJobTemplate template) {
        SlurmJobBuilder builder = new SlurmJobBuilder(template, AGENT_NAME, JENKINS_URL, SECRET);
        JobDescMsg job = builder.build();
        return job.getScript();
    }
}
