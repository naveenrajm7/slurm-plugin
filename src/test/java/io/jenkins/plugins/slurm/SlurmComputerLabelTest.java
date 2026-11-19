package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Space-separated template labels must stay separate atoms on the agent.
 *
 * <p>{@link jenkins.model.Jenkins#getLabel(String)} caches a non-expression string as one
 * {@link LabelAtom}. {@link Label#parse(String)} then returns that atom, so a build asking for
 * {@code gfx1101} never sees the agent.
 */
@WithJenkins
class SlurmComputerLabelTest {

    private static final String LABELS = "navi32 rocmtest gfx1101 miopen";

    @Test
    void acceptingTasks_keepsEachLabelToken(JenkinsRule j) throws Exception {
        SlurmJobTemplate template = SlurmTestHelper.createTemplate("gfx1101", LABELS, 4);
        SlurmCloud cloud = SlurmTestHelper.registerCloudWithTemplate(j.jenkins, "alola", 4, template);

        SlurmAgent first = agent(cloud.name, "alola-gfx1101-1", template.getId(), LABELS);
        j.jenkins.addNode(first);
        SlurmComputer computer = (SlurmComputer) first.toComputer();
        assertNotNull(computer);
        computer.setAcceptingTasks(true);

        assertNull(j.jenkins.tryGetLabelAtom(LABELS), "the joined label string must not become one atom");
        assertEquals(4, Label.parse(LABELS).size());

        SlurmAgent second = agent(cloud.name, "alola-gfx1101-2", template.getId(), LABELS);
        j.jenkins.addNode(second);

        assertSeparateLabels(first);
        assertSeparateLabels(second);

        Label gfx1101 = j.jenkins.getLabel("gfx1101");
        assertNotNull(gfx1101);
        assertTrue(gfx1101.matches(second));
        assertTrue(Label.parseExpression("(rocmtest||migraphx)&&gfx1101&&!vm").matches(second));
        assertTrue(Label.parseExpression("miopen&&(gfx1101||gfx1100)").matches(second));
    }

    private static void assertSeparateLabels(SlurmAgent agent) {
        Set<String> names =
                agent.getAssignedLabels().stream().map(LabelAtom::getName).collect(Collectors.toSet());
        assertTrue(names.contains("navi32"));
        assertTrue(names.contains("rocmtest"));
        assertTrue(names.contains("gfx1101"));
        assertTrue(names.contains("miopen"));
        assertFalse(names.contains(LABELS));
    }

    private static SlurmAgent agent(String cloudName, String name, String templateId, String labels)
            throws Exception {
        return new SlurmAgent(
                name,
                "test agent",
                "/tmp",
                1,
                Node.Mode.NORMAL,
                labels,
                new NoLaunchLauncher(),
                noConnect(),
                Collections.emptyList(),
                cloudName,
                templateId,
                "compute",
                new ProvisioningActivity.Id(cloudName, "template", name));
    }

    /** Does not call {@code connect()}, so the test owns {@code setAcceptingTasks}. */
    private static RetentionStrategy<Computer> noConnect() {
        return new RetentionStrategy<>() {
            @Override
            public long check(Computer c) {
                return 60;
            }

            @Override
            public void start(Computer c) {
                // Leave the computer idle. The test calls setAcceptingTasks itself.
            }
        };
    }
}
