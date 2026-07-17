package io.jenkins.plugins.slurm;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import org.jvnet.hudson.test.TestExtension;

/** Test launcher that keeps manually added agents in Jenkins without submitting Slurm jobs. */
@TestExtension
@Extension
public class NoLaunchLauncher extends ComputerLauncher {
    private static final long serialVersionUID = 1L;

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {
        // Static test agents should remain registered for capacity checks.
    }

    @Extension
    @TestExtension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "NoLaunchLauncher (test)";
        }
    }
}
