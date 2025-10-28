package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.slurm.client.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builder class for creating SLURM job descriptions.
 * 
 * This class takes a {@link SlurmJobTemplate} and converts it into a
 * {@link JobDescMsg} suitable for submission to the SLURM REST API.
 *
 * Similar to Kubernetes plugin's PodTemplateBuilder.
 */
public class SlurmJobBuilder {
    
    private static final Logger LOGGER = Logger.getLogger(SlurmJobBuilder.class.getName());
    
    private final SlurmJobTemplate template;
    private final String agentName;
    private final String jenkinsUrl;
    private final String agentSecret;
    
    /**
     * Creates a new job builder.
     * 
     * @param template The job template to build from
     * @param agentName The unique name for this agent/job
     * @param jenkinsUrl The Jenkins URL for agent connection
     * @param agentSecret The secret for agent authentication
     */
    public SlurmJobBuilder(@NonNull SlurmJobTemplate template, 
                          @NonNull String agentName,
                          @NonNull String jenkinsUrl,
                          @CheckForNull String agentSecret) {
        this.template = template;
        this.agentName = agentName;
        this.jenkinsUrl = jenkinsUrl;
        this.agentSecret = agentSecret;
    }
    
    /**
     * Builds the JobDescMsg for submission to SLURM.
     * 
     * @return The job description message ready for submission
     */
    @NonNull
    public JobDescMsg build() {
        LOGGER.info("Building SLURM job for agent: " + agentName);
        
        JobDescMsg jobDesc = new JobDescMsg();
        
        // Set job name (required)
        jobDesc.setName(agentName);
        
        // Set partition if specified
        if (template.getPartition() != null && !template.getPartition().trim().isEmpty()) {
            jobDesc.setPartition(template.getPartition());
        }
        
        // Set working directory
        if (template.getCurrentWorkingDirectory() != null && !template.getCurrentWorkingDirectory().trim().isEmpty()) {
            jobDesc.setCurrentWorkingDirectory(template.getCurrentWorkingDirectory());
        }
        
        // Set CPU resources
        if (template.getCpusPerTask() != null && template.getCpusPerTask() > 0) {
            jobDesc.setCpusPerTask(template.getCpusPerTask());
        }
        
        // Set memory per node (in MB)
        if (template.getMemoryPerNode() != null && template.getMemoryPerNode() > 0) {
            Uint64NoValStruct memory = new Uint64NoValStruct();
            memory.setSet(true);
            memory.setInfinite(false);
            memory.setNumber(template.getMemoryPerNode());
            jobDesc.setMemoryPerNode(memory);
        }
        
        // Set time limit (in minutes)
        if (template.getTimeLimit() != null && template.getTimeLimit() > 0) {
            Uint32NoValStruct time = new Uint32NoValStruct();
            time.setSet(true);
            time.setInfinite(false);
            time.setNumber(template.getTimeLimit());
            jobDesc.setTimeLimit(time);
        }
        
        // Set node count
        if (template.getMinimumNodes() != null && template.getMinimumNodes() > 0) {
            jobDesc.setMinimumNodes(template.getMinimumNodes());
        }
        
        // Set task count
        if (template.getTasks() != null && template.getTasks() > 0) {
            jobDesc.setTasks(template.getTasks());
        }
        
        // Set TRES for GPUs and other trackable resources
        if (template.getTresPerJob() != null && !template.getTresPerJob().trim().isEmpty()) {
            jobDesc.setTresPerJob(template.getTresPerJob());
        }
        if (template.getTresPerNode() != null && !template.getTresPerNode().trim().isEmpty()) {
            jobDesc.setTresPerNode(template.getTresPerNode());
        }
        if (template.getTresPerTask() != null && !template.getTresPerTask().trim().isEmpty()) {
            jobDesc.setTresPerTask(template.getTresPerTask());
        }
        
        // Set account if specified
        if (template.getAccount() != null && !template.getAccount().trim().isEmpty()) {
            jobDesc.setAccount(template.getAccount());
        }
        
        // Set QoS if specified
        if (template.getQos() != null && !template.getQos().trim().isEmpty()) {
            jobDesc.setQos(template.getQos());
        }
        
        // Set constraints if specified
        if (template.getConstraints() != null && !template.getConstraints().trim().isEmpty()) {
            jobDesc.setConstraints(template.getConstraints());
        }
        
        // Build environment variables
        List<String> environment = buildEnvironment();
        if (!environment.isEmpty()) {
            jobDesc.setEnvironment(environment);
        }
        
        // Generate the batch script
        String script = generateBatchScript();
        jobDesc.setScript(script);
        
        LOGGER.info("Built SLURM job: " + jobDesc.getName() + 
                   " (partition=" + jobDesc.getPartition() + 
                   ", cpus=" + jobDesc.getCpusPerTask() + 
                   ", memory=" + jobDesc.getMemoryPerNode() + "MB)");
        
        return jobDesc;
    }
    
    /**
     * Builds the environment variables for the job.
     * Uses static environment suitable for containerized agents.
     * 
     * @return List of environment variable strings in "KEY=value" format
     */
    private List<String> buildEnvironment() {
        List<String> env = new ArrayList<>();
        
        // Static environment for container-based agents
        env.add("PATH=/usr/local/bin:/usr/bin:/bin");
        env.add("LD_LIBRARY_PATH=/usr/local/lib:/usr/lib");
        
        return env;
    }
    
    /**
     * Generates the batch script for running the Jenkins agent in a container.
     * 
     * Uses container image from Pyxis configuration with pre-baked Jenkins agent.
     * The script format matches the actual working SLURM job submission.
     * 
     * @return The complete batch script content
     */
    private String generateBatchScript() {
        StringBuilder script = new StringBuilder();
        
        script.append("#!/bin/bash\n");
        
        // Add SBATCH directives if nodelist is specified in template constraints
        if (template.getConstraints() != null && !template.getConstraints().trim().isEmpty()) {
            script.append("#SBATCH --nodelist=").append(template.getConstraints()).append("\n");
        }
        
        // Launch Jenkins agent using srun with container
        script.append("srun");
        
        // Add Pyxis/container arguments if configured
        PyxisConfig pyxis = template.getPyxis();
        if (pyxis != null && pyxis.isConfigured()) {
            // Container image (required for Pyxis)
            if (pyxis.getContainerImage() != null && !pyxis.getContainerImage().trim().isEmpty()) {
                script.append(" --container-image=").append(pyxis.getContainerImage());
            }
            
            // Mount home directory flag
            if (pyxis.getContainerMountHome()) {
                script.append(" --container-mount-home");
            }
            
            // Additional mounts
            if (pyxis.getContainerMounts() != null && !pyxis.getContainerMounts().trim().isEmpty()) {
                script.append(" --container-mounts=").append(pyxis.getContainerMounts());
            }
            
            // Container working directory
            if (pyxis.getContainerWorkdir() != null && !pyxis.getContainerWorkdir().trim().isEmpty()) {
                script.append(" --container-workdir=").append(pyxis.getContainerWorkdir());
            }
            
            // Container name
            if (pyxis.getContainerName() != null && !pyxis.getContainerName().trim().isEmpty()) {
                script.append(" --container-name=").append(pyxis.getContainerName());
            }
            
            // Container writable flag
            if (pyxis.getContainerWritable()) {
                script.append(" --container-writable");
            }
            
            // Container remap user flag
            if (pyxis.getContainerRemap()) {
                script.append(" --container-remap-root");
            }
            
            LOGGER.fine("Using Pyxis container configuration: " + pyxis.getContainerImage());
        } else {
            LOGGER.warning("No Pyxis configuration found in template - job will run without container isolation");
        }
        
        // Start Jenkins agent with WebSocket connection (more reliable than direct JNLP)
        script.append(" /opt/java/openjdk/bin/java -jar /usr/share/jenkins/agent.jar");
        script.append(" -url ").append(jenkinsUrl);
        
        if (agentSecret != null && !agentSecret.isEmpty()) {
            script.append(" -secret ").append(agentSecret);
        }
        
        script.append(" -name ").append(agentName);
        script.append(" -webSocket");
        script.append(" -workDir /tmp/").append(agentName);
        script.append("\n");
        
        LOGGER.fine("Generated batch script for agent " + agentName + ":\n" + script.toString());
        
        return script.toString();
    }
    
    /**
     * Validates that the job description is ready for submission.
     * 
     * @param jobDesc The job description to validate
     * @throws IllegalStateException if validation fails
     */
    public static void validate(@NonNull JobDescMsg jobDesc) throws IllegalStateException {
        if (jobDesc.getName() == null || jobDesc.getName().isEmpty()) {
            throw new IllegalStateException("Job name is required");
        }
        
        if (jobDesc.getScript() == null || jobDesc.getScript().isEmpty()) {
            throw new IllegalStateException("Job script is required");
        }
        
        // Warn about common configuration issues
        Uint64NoValStruct memory = jobDesc.getMemoryPerNode();
        if (memory != null && Boolean.TRUE.equals(memory.getSet()) && 
            memory.getNumber() != null && memory.getNumber() < 512) {
            LOGGER.warning("Job " + jobDesc.getName() + " has very low memory: " + 
                          memory.getNumber() + "MB");
        }
        
        Uint32NoValStruct time = jobDesc.getTimeLimit();
        if (time != null && Boolean.TRUE.equals(time.getSet()) && 
            time.getNumber() != null && time.getNumber() < 10) {
            LOGGER.warning("Job " + jobDesc.getName() + " has very short time limit: " + 
                          time.getNumber() + " minutes");
        }
    }
}
