package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import java.io.IOException;
import java.io.Serializable;

/**
 * Node property that exposes Slurm job metadata as build environment variables
 * for all builds running on this agent.
 *
 * <p>Automatically set by the plugin after job submission and compute-node placement;
 * not intended to be created via the Jenkins UI.
 *
 * <p>Contributes:
 * <ul>
 *   <li>{@code SLURM_JOB_ID} — the Slurm job ID assigned at submission time
 *   <li>{@code SLURM_NODELIST} — the compute node(s) allocated once the job is running
 * </ul>
 *
 * <p>These are available in pipeline steps as {@code env.SLURM_JOB_ID} and
 * {@code env.SLURM_NODELIST}, or in shell steps as {@code $SLURM_JOB_ID} and
 * {@code $SLURM_NODELIST}.
 */
public class SlurmEnvironmentNodeProperty extends NodeProperty<Node> implements Serializable {

    private static final long serialVersionUID = 1L;

    @CheckForNull
    private String slurmJobId;

    @CheckForNull
    private String slurmNodeList;

    public SlurmEnvironmentNodeProperty() {}

    public SlurmEnvironmentNodeProperty(
            @CheckForNull String slurmJobId, @CheckForNull String slurmNodeList) {
        this.slurmJobId = slurmJobId;
        this.slurmNodeList = slurmNodeList;
    }

    @CheckForNull
    public String getSlurmJobId() {
        return slurmJobId;
    }

    public void setSlurmJobId(@CheckForNull String slurmJobId) {
        this.slurmJobId = slurmJobId;
    }

    @CheckForNull
    public String getSlurmNodeList() {
        return slurmNodeList;
    }

    public void setSlurmNodeList(@CheckForNull String slurmNodeList) {
        this.slurmNodeList = slurmNodeList;
    }

    @Override
    public void buildEnvVars(@NonNull EnvVars env, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        if (slurmJobId != null && !slurmJobId.isBlank()) {
            env.put("SLURM_JOB_ID", slurmJobId);
        }
        if (slurmNodeList != null && !slurmNodeList.isBlank()) {
            env.put("SLURM_NODELIST", slurmNodeList);
        }
    }

    @Extension
    public static class DescriptorImpl extends NodePropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Slurm environment variables";
        }

        /**
         * Not user-selectable via the Jenkins UI — set automatically by the plugin.
         */
        @Override
        public boolean isApplicable(@SuppressWarnings("rawtypes") Class<? extends Node> nodeType) {
            return false;
        }
    }
}
