package io.jenkins.plugins.slurm.casc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.slurm.SlurmCloud;
import io.jenkins.plugins.slurm.SlurmJobTemplate;
import io.jenkins.plugins.slurm.VmocsConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlurmCasCTest {

    @Test
    void loadsCloudAndTemplatesFromYaml(JenkinsRule j) throws Exception {
        ConfigurationAsCode.get()
                .configure(getClass().getResource("configuration-as-code.yaml").toExternalForm());

        List<SlurmCloud> clouds = j.jenkins.clouds.getAll(SlurmCloud.class);
        assertEquals(1, clouds.size());

        SlurmCloud cloud = clouds.get(0);
        assertNotNull(cloud);
        assertEquals("slurm-casc-roundtrip", cloud.name);
        assertEquals("http://slurm-controller:6820", cloud.getSlurmRestApiUrl());
        assertEquals("slurm-jwt", cloud.getCredentialsId());
        assertEquals("compute", cloud.getDefaultPartition());
        assertEquals(20, cloud.getMaxAgents());
        assertEquals(5, cloud.getAgentTimeoutMinutes());
        assertTrue(cloud.isUsageRestricted());
        assertTrue(cloud.isExplicitLaunchMode());
        assertTrue(cloud.isVmocsLaunch());
        assertNotNull(cloud.getVmocs());
        assertEquals("base-ubuntu", cloud.getVmocs().getVmImage());

        List<SlurmJobTemplate> templates = cloud.getJobTemplates();
        assertEquals(3, templates.size());

        SlurmJobTemplate cpuTemplate = templates.get(0);
        assertEquals("cpu-default", cpuTemplate.getName());
        assertEquals("linux", cpuTemplate.getLabel());
        assertEquals("compute", cpuTemplate.getPartition());
        assertEquals(Integer.valueOf(4), cpuTemplate.getCpusPerTask());
        assertEquals(Long.valueOf(8192), cpuTemplate.getMemoryPerNode());
        assertEquals(Integer.valueOf(120), cpuTemplate.getTimeLimit());
        assertEquals(10, cpuTemplate.getInstanceCap());
        assertEquals(3, cpuTemplate.getIdleMinutes());
        assertTrue(cpuTemplate.isRunOnce());

        SlurmJobTemplate gpuTemplate = templates.get(1);
        assertEquals("gpu-large", gpuTemplate.getName());
        assertEquals("gpu ml", gpuTemplate.getLabel());
        assertEquals("gpu", gpuTemplate.getPartition());
        assertEquals(Integer.valueOf(16), gpuTemplate.getCpusPerTask());
        assertEquals(Long.valueOf(32768), gpuTemplate.getMemoryPerNode());
        assertEquals("gres/gpu:a100:4", gpuTemplate.getTresPerJob());
        assertEquals(Integer.valueOf(480), gpuTemplate.getTimeLimit());
        assertEquals(2, gpuTemplate.getInstanceCap());
        assertFalse(gpuTemplate.isRunOnce());

        SlurmJobTemplate vmTemplate = templates.get(2);
        assertEquals("vm-small", vmTemplate.getName());
        assertEquals("vm", vmTemplate.getLabel());
        assertTrue(vmTemplate.isVmocsLaunch());
        assertFalse(vmTemplate.isNativeLaunch());
        assertFalse(vmTemplate.isPyxisLaunch());
        VmocsConfig vmocs = vmTemplate.getVmocs();
        assertNotNull(vmocs);
        assertEquals("base-ubuntu", vmocs.getVmImage());
        assertEquals("java", vmocs.getJavaPath());
        assertEquals("/tmp/jenkins", vmocs.getWorkDir());
        assertEquals("BUILD_TAG", vmocs.getForwardEnv());
    }
}
