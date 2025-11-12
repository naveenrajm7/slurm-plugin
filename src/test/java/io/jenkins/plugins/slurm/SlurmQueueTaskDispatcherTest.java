package io.jenkins.plugins.slurm;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Calendar;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link SlurmQueueTaskDispatcher}.
 * Pattern inspired by KubernetesQueueTaskDispatcherTest.
 */
public class SlurmQueueTaskDispatcherTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private ExecutorStepExecution.PlaceholderTask task;

    private Folder folderA;
    private Folder folderB;
    private SlurmAgent slaveA;
    private SlurmAgent slaveB;
    private SlurmCloud cloudA;
    private SlurmCloud cloudB;

    @Before
    public void setUp() throws Exception {
        // Create mock task
        task = mock(ExecutorStepExecution.PlaceholderTask.class);
        
        // Create two folders
        folderA = new Folder(r.jenkins, "A");
        folderB = new Folder(r.jenkins, "B");
        r.jenkins.add(folderA, "Folder A");
        r.jenkins.add(folderB, "Folder B");

        // Create two clouds with usage restriction
        cloudA = SlurmTestUtil.createTestCloud("A");
        cloudA.setUsageRestricted(true);

        cloudB = SlurmTestUtil.createTestCloud("B");
        cloudB.setUsageRestricted(true);

        r.jenkins.clouds.add(cloudA);
        r.jenkins.clouds.add(cloudB);

        // Set up folder properties
        SlurmFolderProperty propertyA = new SlurmFolderProperty();
        folderA.addProperty(propertyA);
        JSONObject jsonA = new JSONObject();
        jsonA.element("usage-permission-A", true);
        jsonA.element("usage-permission-B", false);
        folderA.addProperty(propertyA.reconfigure((StaplerRequest2) null, jsonA));

        SlurmFolderProperty propertyB = new SlurmFolderProperty();
        folderB.addProperty(propertyB);
        JSONObject jsonB = new JSONObject();
        jsonB.element("usage-permission-A", false);
        jsonB.element("usage-permission-B", true);
        folderB.addProperty(propertyB.reconfigure((StaplerRequest2) null, jsonB));

        // Create Slurm agents for each cloud
        SlurmJobTemplate templateA = SlurmTestUtil.createTestTemplate("templateA", "testA");
        SlurmJobTemplate templateB = SlurmTestUtil.createTestTemplate("templateB", "testB");
        cloudA.getJobTemplates().add(templateA);
        cloudB.getJobTemplates().add(templateB);

        slaveA = createTestAgent("A", cloudA, templateA);
        slaveB = createTestAgent("B", cloudB, templateB);

        r.jenkins.addNode(slaveA);
        r.jenkins.addNode(slaveB);
    }

    @Test
    public void testRestrictedTwoClouds() throws Exception {
        FreeStyleProject projectA = folderA.createProject(FreeStyleProject.class, "buildJobA");
        FreeStyleProject projectB = folderB.createProject(FreeStyleProject.class, "buildJobB");
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // Folder A can use cloud A
        assertNull(canTake(dispatcher, slaveA, projectA));

        // Folder A cannot use cloud B
        CauseOfBlockage blockageAB = canTake(dispatcher, slaveB, projectA);
        assertNotNull(blockageAB);
        assertTrue(blockageAB instanceof SlurmQueueTaskDispatcher.SlurmCloudNotAllowed);

        // Folder B cannot use cloud A
        CauseOfBlockage blockageBA = canTake(dispatcher, slaveA, projectB);
        assertNotNull(blockageBA);
        assertTrue(blockageBA instanceof SlurmQueueTaskDispatcher.SlurmCloudNotAllowed);

        // Folder B can use cloud B
        assertNull(canTake(dispatcher, slaveB, projectB));
    }

    @Test
    public void testNotRestrictedClouds() throws Exception {
        Folder folder = new Folder(r.jenkins, "C");
        r.jenkins.add(folder, "C");
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "buildJob");

        SlurmCloud cloud = SlurmTestUtil.createTestCloud("C");
        cloud.setUsageRestricted(false);  // Not restricted
        r.jenkins.clouds.add(cloud);

        SlurmJobTemplate template = SlurmTestUtil.createTestTemplate("templateC", "testC");
        cloud.getJobTemplates().add(template);

        SlurmAgent slave = createTestAgent("C", cloud, template);
        r.jenkins.addNode(slave);

        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // Should be allowed (cloud is not restricted)
        assertNull(canTake(dispatcher, slave, project));
    }

    @Test
    public void testDumbSlave() throws Exception {
        DumbSlave slave = r.createOnlineSlave();
        FreeStyleProject project = r.createProject(FreeStyleProject.class);
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // Dumb slave should always be allowed
        assertNull(canTake(dispatcher, slave, project));
    }

    @Test
    public void testPipelinesRestrictedTwoClouds() throws Exception {
        WorkflowJob job = folderA.createProject(WorkflowJob.class, "pipeline");
        when(task.getOwnerTask()).thenReturn(job);
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // Folder A can use cloud A
        assertNull(canTake(dispatcher, slaveA, task));

        // Folder A cannot use cloud B
        CauseOfBlockage blockage = canTake(dispatcher, slaveB, task);
        assertNotNull(blockage);
        assertTrue(blockage instanceof SlurmQueueTaskDispatcher.SlurmCloudNotAllowed);
    }

    @Test
    public void testNonRestrictedAgent() throws Exception {
        // Create non-Slurm agent
        DumbSlave regularSlave = r.createSlave("regular", "", null);
        FreeStyleProject project = folderA.createProject(FreeStyleProject.class, "build");
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // Regular slave should not be restricted
        assertNull(canTake(dispatcher, regularSlave, project));
    }

    @Test
    public void testRootLevelProject() throws Exception {
        // Project at root level (not in folder)
        FreeStyleProject rootProject = r.createProject(FreeStyleProject.class, "rootProject");
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // With restricted cloud, root project should be blocked
        CauseOfBlockage blockage = canTake(dispatcher, slaveA, rootProject);
        assertNotNull(blockage);
        assertTrue(blockage instanceof SlurmQueueTaskDispatcher.SlurmCloudNotAllowed);
    }

    @Test
    public void testMultiplePermittedClouds() throws Exception {
        // Create folder that permits both clouds
        Folder folderC = new Folder(r.jenkins, "C");
        r.jenkins.add(folderC, "Folder C");

        SlurmFolderProperty propertyC = new SlurmFolderProperty();
        folderC.addProperty(propertyC);
        JSONObject jsonC = new JSONObject();
        jsonC.element("usage-permission-A", true);
        jsonC.element("usage-permission-B", true);
        folderC.addProperty(propertyC.reconfigure((StaplerRequest2) null, jsonC));

        FreeStyleProject projectC = folderC.createProject(FreeStyleProject.class, "buildJobC");
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // Folder C can use both clouds
        assertNull(canTake(dispatcher, slaveA, projectC));
        assertNull(canTake(dispatcher, slaveB, projectC));
    }

    @Test
    public void testNestedFolders() throws Exception {
        // Create nested folder structure
        Folder parentFolder = new Folder(r.jenkins, "parent");
        r.jenkins.add(parentFolder, "Parent Folder");

        SlurmFolderProperty parentProperty = new SlurmFolderProperty();
        parentFolder.addProperty(parentProperty);
        JSONObject parentJson = new JSONObject();
        parentJson.element("usage-permission-A", true);
        parentJson.element("usage-permission-B", false);
        parentFolder.addProperty(parentProperty.reconfigure((StaplerRequest2) null, parentJson));

        Folder childFolder = parentFolder.createProject(Folder.class, "child");
        SlurmFolderProperty childProperty = new SlurmFolderProperty();
        childFolder.addProperty(childProperty);
        JSONObject childJson = new JSONObject();
        childJson.element("usage-permission-A", false);
        childJson.element("usage-permission-B", true);
        childFolder.addProperty(childProperty.reconfigure((StaplerRequest2) null, childJson));

        FreeStyleProject childProject = childFolder.createProject(FreeStyleProject.class, "childBuild");
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // Child inherits permissions from parent and adds its own
        // Should be able to use both clouds
        assertNull(canTake(dispatcher, slaveA, childProject));
        assertNull(canTake(dispatcher, slaveB, childProject));
    }

    @Test
    public void testNoFolderProperty() throws Exception {
        // Create folder without Slurm folder property
        Folder folderNoProperty = new Folder(r.jenkins, "no-property");
        r.jenkins.add(folderNoProperty, "No Property Folder");

        FreeStyleProject project = folderNoProperty.createProject(FreeStyleProject.class, "build");
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        // With restricted cloud and no permissions set, should be blocked
        CauseOfBlockage blockage = canTake(dispatcher, slaveA, project);
        assertNotNull(blockage);
        assertTrue(blockage instanceof SlurmQueueTaskDispatcher.SlurmCloudNotAllowed);
    }

    // Helper methods

    private CauseOfBlockage canTake(SlurmQueueTaskDispatcher dispatcher, hudson.model.Node slave, 
                                    hudson.model.Project project) {
        return dispatcher.canTake(
            slave,
            new Queue.BuildableItem(new Queue.WaitingItem(Calendar.getInstance(), project, new ArrayList<>()))
        );
    }

    private CauseOfBlockage canTake(SlurmQueueTaskDispatcher dispatcher, hudson.model.Node slave, 
                                    Queue.Task task) {
        return dispatcher.canTake(
            slave,
            new Queue.BuildableItem(new Queue.WaitingItem(Calendar.getInstance(), task, new ArrayList<>()))
        );
    }

    private SlurmAgent createTestAgent(String name, SlurmCloud cloud, SlurmJobTemplate template) throws Exception {
        SlurmLauncher launcher = new SlurmLauncher();
        RetentionStrategy<?> retentionStrategy = new hudson.slaves.CloudRetentionStrategy(10);
        
        ProvisioningActivity.Id cloudStatsId = new ProvisioningActivity.Id(
            cloud.name,
            template.getId(),
            name
        );

        return new SlurmAgent(
            name,
            "Test Agent",
            template.getCurrentWorkingDirectory(),
            template.getCpusPerTask(),
            Node.Mode.NORMAL,
            template.getLabel(),
            launcher,
            retentionStrategy,
            new ArrayList<>(),
            cloud.name,
            template.getId(),
            template.getPartition(),
            cloudStatsId
        );
    }
}

