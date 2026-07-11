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

    // ------------------------------------------------------------------
    // vmocs tests
    // ------------------------------------------------------------------

    @Test
    public void testVmocsScriptLaunchesVmAndStartsAgentViaSsh() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setTemplateName("base-ubuntu");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("vmocs"), "script must invoke vmocs");
        assertTrue(script.contains("launch"), "script must call vmocs launch");
        assertTrue(script.contains("'base-ubuntu'"), "script must reference the VM template name");
        assertTrue(script.contains("--job-id"), "script must pass --job-id to vmocs");
        assertTrue(script.contains("grep -q '^ssh '"), "script must wait for SSH readiness");
        assertTrue(script.contains("ssh "), "script must SSH into the VM");
        assertTrue(script.contains("-webSocket"), "agent must use WebSocket connection");
        assertTrue(script.contains(JENKINS_URL), "script must embed the Jenkins URL");
        assertTrue(script.contains(AGENT_NAME), "script must embed the agent name");
    }

    @Test
    public void testVmocsScriptIncludesCoresAndMemory() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setTemplateName("base-ubuntu");
        vmocs.setCores(8);
        vmocs.setMemoryMb(16384);
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("--cores 8"), "script must pass --cores");
        assertTrue(script.contains("--memory 16384"), "script must pass --memory");
    }

    @Test
    public void testVmocsScriptExpandsPciDevices() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setTemplateName("gpu-vm");
        vmocs.setPciDevices("0000:03:00.0,0000:03:00.1");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("--pci '0000:03:00.0'"), "each BDF must become a --pci flag");
        assertTrue(script.contains("--pci '0000:03:00.1'"), "second BDF must also become a --pci flag");
    }

    @Test
    public void testVmocsScriptUsesCustomBinAndConfigPath() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setTemplateName("base-ubuntu");
        vmocs.setVmocsBin("/usr/local/bin/vmocs");
        vmocs.setConfigPath("/etc/vmocs/vmocs.yaml");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("'/usr/local/bin/vmocs'"), "script must use custom vmocs binary path");
        assertTrue(script.contains("--config '/etc/vmocs/vmocs.yaml'"), "script must pass --config");
    }

    @Test
    public void testVmocsScriptUsesPreinstalledJar() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setTemplateName("base-ubuntu");
        vmocs.setAgentJarPath("/opt/jenkins/agent.jar");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("/opt/jenkins/agent.jar"), "script must reference pre-installed jar");
        assertTrue(
                !script.contains("jnlpJars/agent.jar"), "script must NOT download jar when pre-installed path is set");
        assertTrue(
                !script.contains("curl")
                        || script.contains("curl") && script.indexOf("curl") > script.indexOf("agent.jar"),
                "scp/download block should be absent when agentJarPath is set");
    }

    @Test
    public void testVmocsScriptDownloadsJarWhenNoPreinstalledPath() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setTemplateName("base-ubuntu");
        // agentJarPath left empty → download from Jenkins
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("jnlpJars/agent.jar"), "script must download agent.jar when no pre-installed path");
        assertTrue(script.contains("scp "), "script must scp the jar into the VM");
    }

    @Test
    public void testVmocsTakesPriorityOverNativeWhenBothConfigured() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setTemplateName("base-ubuntu");
        template.setVmocs(vmocs);

        AgentLaunchConfig agent = new AgentLaunchConfig();
        agent.setJarPath("/opt/jenkins/agent.jar");
        template.setAgent(agent);

        String script = buildScript(template);

        // vmocs wins — no srun invocation, SSH into VM instead
        assertTrue(script.contains("vmocs"), "vmocs must be used when both vmocs and native are configured");
        assertTrue(!script.contains("srun -N1 -n1"), "native srun must not be used when vmocs is configured");
    }

    @Test
    public void testPyxisTakesPriorityOverVmocs() {
        SlurmJobTemplate template = baseTemplate();

        PyxisConfig pyxis = new PyxisConfig();
        pyxis.setContainerImage("/path/to/image.sqsh");
        template.setPyxis(pyxis);

        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setTemplateName("base-ubuntu");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        // Pyxis wins
        assertTrue(script.contains("--container-image"), "Pyxis must take priority over vmocs");
        assertTrue(!script.contains("vmocs launch"), "vmocs must not be used when Pyxis is configured");
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
