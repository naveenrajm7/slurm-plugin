package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlurmFolderPropertyTest {

    @Test
    void propertySavedOnFirstSave(JenkinsRule j) throws Exception {
        SlurmCloud cloudOne = SlurmTestHelper.createCloud("slurm-one", 10);
        cloudOne.setUsageRestricted(true);
        SlurmCloud cloudTwo = SlurmTestHelper.createCloud("slurm-two", 10);
        cloudTwo.setUsageRestricted(true);
        j.jenkins.clouds.add(cloudOne);
        j.jenkins.clouds.add(cloudTwo);

        Folder folder = j.jenkins.createProject(Folder.class, "folder001");
        SlurmFolderProperty property = new SlurmFolderProperty();
        folder.addProperty(property);

        Folder afterRoundTrip = j.configRoundtrip(folder);
        SlurmFolderProperty reloaded = afterRoundTrip.getProperties().get(SlurmFolderProperty.class);
        assertNotNull(reloaded);
        assertTrue(reloaded.getPermittedClouds().isEmpty());

        reloaded.setPermittedClouds(Collections.singletonList("slurm-one"));
        afterRoundTrip = j.configRoundtrip(folder);
        assertEquals(
                Collections.singletonList("slurm-one"),
                afterRoundTrip.getProperties().get(SlurmFolderProperty.class).getPermittedClouds());
    }

    @Test
    void collectAllowedClouds_includesParentAndChildFolders(JenkinsRule j) throws Exception {
        SlurmCloud cloudOne = SlurmTestHelper.createCloud("slurm-one", 10);
        cloudOne.setUsageRestricted(true);
        SlurmCloud cloudTwo = SlurmTestHelper.createCloud("slurm-two", 10);
        cloudTwo.setUsageRestricted(true);
        j.jenkins.clouds.add(cloudOne);
        j.jenkins.clouds.add(cloudTwo);

        Folder parent = j.jenkins.createProject(Folder.class, "parent");
        SlurmFolderProperty parentProperty = new SlurmFolderProperty();
        parentProperty.setPermittedClouds(Collections.singletonList("slurm-one"));
        parent.addProperty(parentProperty);

        Folder child = parent.createProject(Folder.class, "child");
        SlurmFolderProperty childProperty = new SlurmFolderProperty();
        childProperty.setPermittedClouds(Collections.singletonList("slurm-two"));
        child.addProperty(childProperty);

        FreeStyleProject job = child.createProject(FreeStyleProject.class, "job");
        Set<String> allowed = new HashSet<>();
        SlurmFolderProperty.collectAllowedClouds(allowed, job.getParent());

        assertTrue(allowed.contains("slurm-one"));
        assertTrue(allowed.contains("slurm-two"));
        assertEquals(2, allowed.size());
    }

    @Test
    void isAllowed_respectsFolderRestrictions(JenkinsRule j) throws Exception {
        SlurmCloud allowedCloud = SlurmTestHelper.createCloud("allowed", 10);
        allowedCloud.setUsageRestricted(true);
        SlurmCloud deniedCloud = SlurmTestHelper.createCloud("denied", 10);
        deniedCloud.setUsageRestricted(true);
        j.jenkins.clouds.add(allowedCloud);
        j.jenkins.clouds.add(deniedCloud);

        Folder folder = j.jenkins.createProject(Folder.class, "restricted");
        SlurmFolderProperty property = new SlurmFolderProperty();
        property.setPermittedClouds(Collections.singletonList("allowed"));
        folder.addProperty(property);

        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "build");
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);

        SlurmAgent permittedAgent = SlurmTestHelper.createAgent("permitted", "allowed", template.getId());
        SlurmAgent deniedAgent = SlurmTestHelper.createAgent("denied-agent", "denied", template.getId());

        assertTrue(SlurmFolderProperty.isAllowed(permittedAgent, job));
        assertFalse(SlurmFolderProperty.isAllowed(deniedAgent, job));
    }

    @Test
    void isAllowed_allowsUnrestrictedClouds(JenkinsRule j) throws Exception {
        SlurmCloud cloud = SlurmTestHelper.createCloud("open", 10);
        cloud.setUsageRestricted(false);
        j.jenkins.clouds.add(cloud);

        Folder folder = j.jenkins.createProject(Folder.class, "open-folder");
        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "build");
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("cpu", "linux", 1);
        SlurmAgent agent = SlurmTestHelper.createAgent("open-agent", "open", template.getId());

        assertTrue(SlurmFolderProperty.isAllowed(agent, job));
    }

    @Test
    void isAllowed_cloudOverload_respectsFolderRestrictions(JenkinsRule j) throws Exception {
        SlurmCloud allowedCloud = SlurmTestHelper.createCloud("allowed", 10);
        allowedCloud.setUsageRestricted(true);
        SlurmCloud deniedCloud = SlurmTestHelper.createCloud("denied", 10);
        deniedCloud.setUsageRestricted(true);
        j.jenkins.clouds.add(allowedCloud);
        j.jenkins.clouds.add(deniedCloud);

        Folder folder = j.jenkins.createProject(Folder.class, "restricted-cloud-overload");
        SlurmFolderProperty property = new SlurmFolderProperty();
        property.setPermittedClouds(Collections.singletonList("allowed"));
        folder.addProperty(property);

        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "build");

        assertTrue(SlurmFolderProperty.isAllowed(allowedCloud, job));
        assertFalse(SlurmFolderProperty.isAllowed(deniedCloud, job));
    }

    @Test
    void isAllowed_cloudOverload_allowsUnrestrictedCloud(JenkinsRule j) throws Exception {
        SlurmCloud cloud = SlurmTestHelper.createCloud("open", 10);
        cloud.setUsageRestricted(false);
        j.jenkins.clouds.add(cloud);

        Folder folder = j.jenkins.createProject(Folder.class, "open-cloud-overload");
        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "build");

        assertTrue(SlurmFolderProperty.isAllowed(cloud, job));
    }

    @Test
    void isAllowed_cloudOverload_deniesRestrictedCloudWithNullJob(JenkinsRule j) throws Exception {
        SlurmCloud restricted = SlurmTestHelper.createCloud("restricted", 10);
        restricted.setUsageRestricted(true);
        SlurmCloud unrestricted = SlurmTestHelper.createCloud("unrestricted", 10);
        unrestricted.setUsageRestricted(false);
        j.jenkins.clouds.add(restricted);
        j.jenkins.clouds.add(unrestricted);

        assertFalse(SlurmFolderProperty.isAllowed(restricted, null));
        assertTrue(SlurmFolderProperty.isAllowed(unrestricted, null));
    }
}
