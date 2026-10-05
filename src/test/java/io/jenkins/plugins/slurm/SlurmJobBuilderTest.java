package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.slurm.client.model.JobDescMsg;
import java.util.List;
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
        assertTrue(script.contains("-workDir /tmp/" + AGENT_NAME));
        assertFalse(script.contains("JENKINS_AGENT_ROOT"));
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
        assertTrue(script.contains("-workDir \"$JENKINS_AGENT_ROOT\""));
    }

    @Test
    public void testNativeScriptIsolatesWorkspacePerAgent() {
        SlurmJobTemplate template = baseTemplate();
        template.setCurrentWorkingDirectory("/var/jenkins_home/");
        AgentLaunchConfig agent = new AgentLaunchConfig();
        agent.setJarPath("/opt/jenkins/agent.jar");
        template.setAgent(agent);

        JobDescMsg job = new SlurmJobBuilder(template, AGENT_NAME, JENKINS_URL, SECRET).build();
        String script = job.getScript();

        assertEquals("/var/jenkins_home/", job.getCurrentWorkingDirectory());
        assertTrue(script.contains("JENKINS_AGENT_ROOT='/var/jenkins_home/agents/" + AGENT_NAME + "'"));
        assertTrue(script.contains("mkdir -p \"$JENKINS_AGENT_ROOT\""));
        assertTrue(script.contains("trap 'rm -rf \"$JENKINS_AGENT_ROOT\"' EXIT"));
        assertTrue(script.contains("trap 'exit 143' TERM"));
        assertTrue(script.contains("-workDir \"$JENKINS_AGENT_ROOT\""));
        assertFalse(script.contains("-workDir /tmp/" + AGENT_NAME));
        assertEquals(
                "/var/jenkins_home/agents/" + AGENT_NAME,
                SlurmJobBuilder.nativeAgentRoot("/var/jenkins_home/", AGENT_NAME));
        assertEquals("/tmp/jenkins/agents/" + AGENT_NAME, SlurmJobBuilder.nativeAgentRoot("  ", AGENT_NAME));
    }

    @Test
    public void testPerAgentWorkspaceSkippedForCustomScriptAndPyxis() {
        SlurmJobTemplate nativeTemplate = baseTemplate();
        assertTrue(SlurmJobBuilder.usesPerAgentWorkspace(nativeTemplate));

        nativeTemplate.setScript("#!/bin/bash\necho custom\n");
        assertFalse(SlurmJobBuilder.usesPerAgentWorkspace(nativeTemplate));

        SlurmJobTemplate pyxisTemplate = baseTemplate();
        pyxisTemplate.setLaunchMode("PYXIS");
        PyxisConfig pyxis = new PyxisConfig();
        pyxis.setContainerImage("/path/to/image.sqsh");
        pyxisTemplate.setPyxis(pyxis);
        assertFalse(SlurmJobBuilder.usesPerAgentWorkspace(pyxisTemplate));
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

    @Nested
    class EnvironmentTest {

        @Test
        void commaSeparatedValuesStayIntact() {
            SlurmJobTemplate template = baseTemplate();
            template.setEnvironment(
                    "[\"NUMBER_OF_EXECUTORS=1\",\"DOCKER_GPU_MASK_0=0,1,2,3\","
                            + "\"DOCKER_GPU_MASK_ROCR_0=--env ROCR_VISIBLE_DEVICES=0,1,2,3\"]");

            List<String> env = buildEnvironment(template);

            assertTrue(env.contains("NUMBER_OF_EXECUTORS=1"));
            assertTrue(env.contains("DOCKER_GPU_MASK_0=0,1,2,3"));
            assertTrue(env.contains("DOCKER_GPU_MASK_ROCR_0=--env ROCR_VISIBLE_DEVICES=0,1,2,3"));
            assertFalse(env.stream().anyMatch(entry -> entry.startsWith("\"") || entry.contains("\"DOCKER")));
        }

        @Test
        void singleValueWithoutCommaStillRoundTrips() {
            SlurmJobTemplate template = baseTemplate();
            template.setEnvironment("[\"DOCKER_GPU_MASK_0=0\"]");

            List<String> env = buildEnvironment(template);

            assertTrue(env.contains("DOCKER_GPU_MASK_0=0"));
        }

        @Test
        void spacesAndEscapedQuotesArePreserved() {
            SlurmJobTemplate template = baseTemplate();
            template.setEnvironment("[\"GREETING=say \\\"hello, world\\\"\",\"CONFIG={\\\"a\\\":1}\"]");

            List<String> env = buildEnvironment(template);

            assertTrue(env.contains("GREETING=say \"hello, world\""));
            assertTrue(env.contains("CONFIG={\"a\":1}"));
        }

        @Test
        void requiredPathVariablesAreNotOverridden() {
            SlurmJobTemplate template = baseTemplate();
            template.setEnvironment("[\"PATH=/evil/bin\",\"LD_LIBRARY_PATH=/evil/lib\",\"OK=1\"]");

            List<String> env = buildEnvironment(template);

            assertEquals("PATH=/usr/local/bin:/usr/bin:/bin", env.get(0));
            assertEquals("LD_LIBRARY_PATH=/usr/local/lib:/usr/lib", env.get(1));
            assertTrue(env.contains("OK=1"));
            assertFalse(env.contains("PATH=/evil/bin"));
            assertFalse(env.contains("LD_LIBRARY_PATH=/evil/lib"));
        }

        @Test
        void invalidJsonKeepsOnlyRequiredVariables() {
            SlurmJobTemplate template = baseTemplate();
            template.setEnvironment("[not-json");

            List<String> env = buildEnvironment(template);

            assertEquals(List.of("PATH=/usr/local/bin:/usr/bin:/bin", "LD_LIBRARY_PATH=/usr/local/lib:/usr/lib"), env);
        }

        @Test
        void nonArrayEnvironmentIsIgnored() {
            SlurmJobTemplate template = baseTemplate();
            template.setEnvironment("VISIBLE_DEVICES=0,1,2,3");

            List<String> env = buildEnvironment(template);

            assertEquals(List.of("PATH=/usr/local/bin:/usr/bin:/bin", "LD_LIBRARY_PATH=/usr/local/lib:/usr/lib"), env);
        }

        @Test
        void entriesWithoutEqualsAreDropped() {
            SlurmJobTemplate template = baseTemplate();
            template.setEnvironment("[\"NOEQUALS\",\"OK=1\",\"\"]");

            List<String> env = buildEnvironment(template);

            assertTrue(env.contains("OK=1"));
            assertFalse(env.contains("NOEQUALS"));
            assertEquals(3, env.size());
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

    private static List<String> buildEnvironment(SlurmJobTemplate template) {
        template.setScript("#!/bin/bash\ntrue\n");
        return new SlurmJobBuilder(template, AGENT_NAME, JENKINS_URL, SECRET)
                .build()
                .getEnvironment();
    }
}
