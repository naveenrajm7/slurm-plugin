package io.jenkins.plugins.slurm;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.model.TransientComputerActionFactory;
import java.util.Collection;
import java.util.Collections;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Renders live Slurm job status on the agent page while provisioning.
 */
@ExportedBean
public class SlurmJobStatusAction implements Action {

    private final SlurmComputer computer;

    public SlurmJobStatusAction(SlurmComputer computer) {
        this.computer = computer;
    }

    public SlurmComputer getComputer() {
        return computer;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }

    @Extension
    public static final class Factory extends TransientComputerActionFactory {
        @Override
        public Collection<? extends Action> createFor(Computer target) {
            if (target instanceof SlurmComputer) {
                return Collections.singleton(new SlurmJobStatusAction((SlurmComputer) target));
            }
            return Collections.emptyList();
        }
    }
}
