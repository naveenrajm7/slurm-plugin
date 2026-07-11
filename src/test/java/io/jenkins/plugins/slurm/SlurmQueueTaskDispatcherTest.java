package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.DumbSlave;
import java.util.ArrayList;
import java.util.Calendar;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

@WithJenkins
class SlurmQueueTaskDispatcherTest {

    private Folder folderA;
    private Folder folderB;
    private SlurmAgent agentA;
    private SlurmAgent agentB;

    private void setUpTwoClouds(JenkinsRule j) throws Exception {
        folderA = new Folder(j.jenkins, "A");
        folderB = new Folder(j.jenkins, "B");
        j.jenkins.add(folderA, "Folder A");
        j.jenkins.add(folderB, "Folder B");

        SlurmCloud cloudA = SlurmTestHelper.createCloud("A", 10);
        cloudA.setUsageRestricted(true);
        SlurmCloud cloudB = SlurmTestHelper.createCloud("B", 10);
        cloudB.setUsageRestricted(true);
        j.jenkins.clouds.add(cloudA);
        j.jenkins.clouds.add(cloudB);

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

        SlurmJobTemplate templateA = SlurmTestHelper.createTemplate("tpl-a", "linux", 1);
        SlurmJobTemplate templateB = SlurmTestHelper.createTemplate("tpl-b", "linux", 1);
        agentA = SlurmTestHelper.createAgent("agent-a", "A", templateA.getId());
        agentB = SlurmTestHelper.createAgent("agent-b", "B", templateB.getId());
    }

    @Test
    void checkRestrictedTwoClouds(JenkinsRule j) throws Exception {
        setUpTwoClouds(j);
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        FreeStyleProject projectA = folderA.createProject(FreeStyleProject.class, "buildJob");
        FreeStyleProject projectB = folderB.createProject(FreeStyleProject.class, "buildJob");

        assertNull(canTake(dispatcher, agentA, projectA));
        assertInstanceOf(SlurmQueueTaskDispatcher.SlurmCloudNotAllowed.class, canTake(dispatcher, agentB, projectA));
        assertInstanceOf(SlurmQueueTaskDispatcher.SlurmCloudNotAllowed.class, canTake(dispatcher, agentA, projectB));
        assertNull(canTake(dispatcher, agentB, projectB));
    }

    @Test
    void checkNotRestrictedClouds(JenkinsRule j) throws Exception {
        Folder folder = new Folder(j.jenkins, "C");
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "buildJob");
        j.jenkins.add(folder, "C");

        SlurmCloud cloud = SlurmTestHelper.createCloud("C", 10);
        cloud.setUsageRestricted(false);
        j.jenkins.clouds.add(cloud);

        SlurmJobTemplate template = SlurmTestHelper.createTemplate("tpl-c", "linux", 1);
        SlurmAgent agent = SlurmTestHelper.createAgent("agent-c", "C", template.getId());
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        assertNull(canTake(dispatcher, agent, project));
    }

    @Test
    void checkDumbSlave(JenkinsRule j) throws Exception {
        DumbSlave slave = j.createOnlineSlave();
        FreeStyleProject project = j.createProject(FreeStyleProject.class);
        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        assertNull(canTake(dispatcher, slave, project));
    }

    @Test
    void checkPipelinesRestrictedTwoClouds(JenkinsRule j) throws Exception {
        setUpTwoClouds(j);

        WorkflowJob job = folderA.createProject(WorkflowJob.class, "pipeline");
        ExecutorStepExecution.PlaceholderTask placeholderTask = mock(ExecutorStepExecution.PlaceholderTask.class);
        when(placeholderTask.getOwnerTask()).thenReturn(job);

        SlurmQueueTaskDispatcher dispatcher = new SlurmQueueTaskDispatcher();

        assertNull(canTake(dispatcher, agentA, placeholderTask));
        assertInstanceOf(
                SlurmQueueTaskDispatcher.SlurmCloudNotAllowed.class, canTake(dispatcher, agentB, placeholderTask));
    }

    private CauseOfBlockage canTake(
            SlurmQueueTaskDispatcher dispatcher, hudson.model.Node node, hudson.model.Project<?, ?> project) {
        return dispatcher.canTake(
                node,
                new Queue.BuildableItem(new Queue.WaitingItem(Calendar.getInstance(), project, new ArrayList<>())));
    }

    private CauseOfBlockage canTake(SlurmQueueTaskDispatcher dispatcher, hudson.model.Node node, Queue.Task task) {
        return dispatcher.canTake(
                node, new Queue.BuildableItem(new Queue.WaitingItem(Calendar.getInstance(), task, new ArrayList<>())));
    }
}
