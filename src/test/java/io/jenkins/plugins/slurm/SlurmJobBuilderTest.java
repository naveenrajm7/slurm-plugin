package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ------------------------------------------------------------------
    // vmocs tests — the vmocs SPANK plugin manages the VM lifecycle;
    // our script emits #SBATCH --vm-image= and then SSH-connects to the VM.
    // ------------------------------------------------------------------

    @Test
    public void testVmocsScriptContainsSbatchVmImageDirective() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setVmImage("base-ubuntu");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(
                script.contains("#SBATCH --vm-image=base-ubuntu"),
                "script must emit the #SBATCH --vm-image= directive for the SPANK plugin");
    }

    @Test
    public void testVmocsScriptWaitsForSshPortAndConnects() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setVmImage("base-ubuntu");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("nc -z 127.0.0.1"), "script must poll SSH port readiness");
        assertTrue(script.contains("ssh "), "script must SSH into the VM");
        assertTrue(script.contains("-webSocket"), "agent must use WebSocket connection");
        assertTrue(script.contains(JENKINS_URL), "script must embed the Jenkins URL");
        assertTrue(script.contains(AGENT_NAME), "script must embed the agent name");
    }

    @Test
    public void testVmocsScriptUsesConfiguredSshPortAndUser() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setVmImage("base-ubuntu");
        vmocs.setSshPort(60300);
        vmocs.setSshUser("ubuntu");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("nc -z 127.0.0.1 60300"), "script must poll configured SSH port");
        assertTrue(script.contains("-p 60300"), "ssh command must use configured port");
        assertTrue(script.contains("'ubuntu@127.0.0.1'"), "ssh command must use configured user");
    }

    @Test
    public void testVmocsScriptIncludesSshKeyWhenConfigured() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setVmImage("base-ubuntu");
        vmocs.setSshKeyPath("/opt/vmocs/keys/vagrant_insecure_key");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(
                script.contains("-i '/opt/vmocs/keys/vagrant_insecure_key'"),
                "SSH options must include the configured private key path");
    }

    @Test
    public void testVmocsScriptUsesPreinstalledJar() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setVmImage("base-ubuntu");
        vmocs.setAgentJarPath("/opt/jenkins/agent.jar");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("/opt/jenkins/agent.jar"), "script must reference pre-installed jar");
        assertFalse(
                script.contains("jnlpJars/agent.jar"), "script must NOT download jar when pre-installed path is set");
    }

    @Test
    public void testVmocsScriptDownloadsAndScpsJarWhenNoPreinstalledPath() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setVmImage("base-ubuntu");
        // agentJarPath left empty → download from Jenkins controller
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("jnlpJars/agent.jar"), "script must download agent.jar");
        assertTrue(script.contains("scp "), "script must scp the jar into the VM");
    }

    @Test
    public void testVmocsTakesPriorityOverNative() {
        SlurmJobTemplate template = baseTemplate();
        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setVmImage("base-ubuntu");
        template.setVmocs(vmocs);

        AgentLaunchConfig agent = new AgentLaunchConfig();
        agent.setJarPath("/opt/jenkins/agent.jar");
        template.setAgent(agent);

        String script = buildScript(template);

        assertTrue(
                script.contains("#SBATCH --vm-image="), "vmocs must be used when both vmocs and native are configured");
        assertFalse(script.contains("srun -N1 -n1"), "native srun must not be used when vmocs is configured");
    }

    @Test
    public void testPyxisTakesPriorityOverVmocs() {
        SlurmJobTemplate template = baseTemplate();

        PyxisConfig pyxis = new PyxisConfig();
        pyxis.setContainerImage("/path/to/image.sqsh");
        template.setPyxis(pyxis);

        VmocsConfig vmocs = new VmocsConfig();
        vmocs.setVmImage("base-ubuntu");
        template.setVmocs(vmocs);

        String script = buildScript(template);

        assertTrue(script.contains("--container-image"), "Pyxis must take priority over vmocs");
        assertFalse(script.contains("#SBATCH --vm-image="), "vmocs directive must not appear when Pyxis is configured");
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
