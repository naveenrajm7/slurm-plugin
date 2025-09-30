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
 * Builder class that constructs SLURM job submission objects from job templates.
 * 
 * This class takes a {@link SlurmJobTemplate} and converts it into a 
 * {@link V0042JobDescMsg} suitable for submission to the SLURM REST API.
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
     * Builds the V0042JobDescMsg for submission to SLURM.
     * 
     * @return The job description message ready for submission
     */
    @NonNull
    public V0042JobDescMsg build() {
        LOGGER.info("Building SLURM job for agent: " + agentName);
        
        V0042JobDescMsg jobDesc = new V0042JobDescMsg();
        
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
            V0042Uint64NoValStruct memory = new V0042Uint64NoValStruct();
            memory.setSet(true);
            memory.setInfinite(false);
            memory.setNumber(template.getMemoryPerNode());
            jobDesc.setMemoryPerNode(memory);
        }
        
        // Set time limit (in minutes)
        if (template.getTimeLimit() != null && template.getTimeLimit() > 0) {
            V0042Uint32NoValStruct time = new V0042Uint32NoValStruct();
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
     * Includes Jenkins-specific variables and user-defined variables from template.
     * 
     * @return List of environment variable strings in "KEY=value" format
     */
    private List<String> buildEnvironment() {
        List<String> env = new ArrayList<>();
        
        // Add Jenkins agent environment variables
        env.add("JENKINS_URL=" + jenkinsUrl);
        env.add("JENKINS_AGENT_NAME=" + agentName);
        
        if (agentSecret != null && !agentSecret.isEmpty()) {
            env.add("JENKINS_SECRET=" + agentSecret);
        }
        
        // Add working directory
        if (template.getCurrentWorkingDirectory() != null) {
            env.add("JENKINS_AGENT_WORKDIR=" + template.getCurrentWorkingDirectory());
        }
        
        // Parse and add user-defined environment variables from template
        // Template environment is stored as comma-separated KEY=value pairs
        if (template.getEnvironment() != null && !template.getEnvironment().trim().isEmpty()) {
            String[] userEnvVars = template.getEnvironment().split(",");
            for (String envVar : userEnvVars) {
                String trimmed = envVar.trim();
                if (!trimmed.isEmpty() && trimmed.contains("=")) {
                    env.add(trimmed);
                }
            }
        }
        
        return env;
    }
    
    /**
     * Generates the batch script for running the Jenkins agent.
     * 
     * The script will:
     * 1. Set up the working directory
     * 2. Download Jenkins agent JAR if needed
     * 3. Start the Jenkins agent with proper connection parameters
     * 
     * If the template has a custom script, it will be prepended to the agent startup.
     * 
     * @return The complete batch script content
     */
    private String generateBatchScript() {
        StringBuilder script = new StringBuilder();
        
        script.append("#!/bin/bash\n");
        script.append("# SLURM Jenkins Agent Launcher\n");
        script.append("# Agent: ").append(agentName).append("\n");
        script.append("# Generated by Jenkins SLURM Plugin\n\n");
        
        // Set error handling
        script.append("set -e\n");
        script.append("set -u\n\n");
        
        // Create and navigate to working directory
        String workDir = template.getCurrentWorkingDirectory();
        if (workDir != null && !workDir.isEmpty()) {
            script.append("# Create working directory\n");
            script.append("mkdir -p ").append(workDir).append("\n");
            script.append("cd ").append(workDir).append("\n\n");
        }
        
        // Add custom script from template if provided
        if (template.getScript() != null && !template.getScript().trim().isEmpty()) {
            script.append("# Custom user script\n");
            script.append(template.getScript());
            if (!template.getScript().endsWith("\n")) {
                script.append("\n");
            }
            script.append("\n");
        }
        
        // Download agent.jar if not present
        script.append("# Download Jenkins agent JAR\n");
        script.append("if [ ! -f agent.jar ]; then\n");
        script.append("  echo 'Downloading Jenkins agent JAR...'\n");
        script.append("  wget -q -O agent.jar ${JENKINS_URL}jnlpJars/agent.jar || curl -sSL -o agent.jar ${JENKINS_URL}jnlpJars/agent.jar\n");
        script.append("  if [ $? -ne 0 ]; then\n");
        script.append("    echo 'ERROR: Failed to download agent JAR from ${JENKINS_URL}'\n");
        script.append("    exit 1\n");
        script.append("  fi\n");
        script.append("fi\n\n");
        
        // Start Jenkins agent
        script.append("# Start Jenkins agent\n");
        script.append("echo 'Starting Jenkins agent: ${JENKINS_AGENT_NAME}'\n");
        script.append("echo 'Connecting to: ${JENKINS_URL}'\n\n");
        
        // Use JNLP connection mode
        script.append("java -jar agent.jar \\\n");
        script.append("  -url ${JENKINS_URL} \\\n");
        script.append("  -name ${JENKINS_AGENT_NAME} \\\n");
        
        if (agentSecret != null && !agentSecret.isEmpty()) {
            script.append("  -secret ${JENKINS_SECRET} \\\n");
        }
        
        script.append("  -workDir ${JENKINS_AGENT_WORKDIR:-.} \\\n");
        script.append("  -jar-cache ${JENKINS_AGENT_WORKDIR:-.}/remoting\n\n");
        
        script.append("echo 'Jenkins agent exited'\n");
        
        return script.toString();
    }
    
    /**
     * Validates that the job description is ready for submission.
     * 
     * @param jobDesc The job description to validate
     * @throws IllegalStateException if validation fails
     */
    public static void validate(@NonNull V0042JobDescMsg jobDesc) throws IllegalStateException {
        if (jobDesc.getName() == null || jobDesc.getName().isEmpty()) {
            throw new IllegalStateException("Job name is required");
        }
        
        if (jobDesc.getScript() == null || jobDesc.getScript().isEmpty()) {
            throw new IllegalStateException("Job script is required");
        }
        
        // Warn about common configuration issues
        V0042Uint64NoValStruct memory = jobDesc.getMemoryPerNode();
        if (memory != null && Boolean.TRUE.equals(memory.getSet()) && 
            memory.getNumber() != null && memory.getNumber() < 512) {
            LOGGER.warning("Job " + jobDesc.getName() + " has very low memory: " + 
                          memory.getNumber() + "MB");
        }
        
        V0042Uint32NoValStruct time = jobDesc.getTimeLimit();
        if (time != null && Boolean.TRUE.equals(time.getSet()) && 
            time.getNumber() != null && time.getNumber() < 10) {
            LOGGER.warning("Job " + jobDesc.getName() + " has very short time limit: " + 
                          time.getNumber() + " minutes");
        }
    }
}
