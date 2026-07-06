package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.slurm.client.model.JobDescMsg;
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
        assertTrue(!script.contains("--container-image"));
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
        assertTrue(!script.contains("# comment"));
    }

    @Test
    public void testCloudDefaultsUsedWhenTemplateHasNoAgent() {
        SlurmJobTemplate template = baseTemplate();

        AgentLaunchConfig cloudAgent = new AgentLaunchConfig();
        cloudAgent.setJavaPath("/opt/jenkins/jdk-17/bin/java");
        cloudAgent.setJarPath("/opt/jenkins/agent.jar");

        SlurmJobBuilder builder = new SlurmJobBuilder(
                template, AGENT_NAME, JENKINS_URL, SECRET, cloudAgent);
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
