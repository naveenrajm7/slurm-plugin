/*
 * The MIT License
 *
 * Copyright (c) 2025
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.jenkins.plugins.slurm.pipeline

import org.jenkinsci.plugins.pipeline.modeldefinition.agent.CheckoutScript
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentScript
import org.jenkinsci.plugins.workflow.cps.CpsScript

/**
 * Script execution for SLURM declarative agents.
 * 
 * This script:
 * 1. Creates a SLURM job template step with the specified configuration
 * 2. Launches a SLURM agent within that template
 * 3. Performs SCM checkout if needed
 * 4. Executes the pipeline body
 * 5. Cleans up the SLURM job
 */
public class SlurmDeclarativeAgentScript extends DeclarativeAgentScript<SlurmDeclarativeAgent> {
    
    public SlurmDeclarativeAgentScript(CpsScript s, SlurmDeclarativeAgent a) {
        super(s, a)
        s.echo "=== SLURM DECLARATIVE AGENT SCRIPT CONSTRUCTED ==="
        s.echo "Cloud: ${a.cloud}"
        s.echo "Label: ${a.label}"
    }

    @Override
    public Closure run(Closure body) {
        script.echo "=== SLURM DECLARATIVE AGENT SCRIPT STARTED ==="
        script.echo "Describable class: ${describable.getClass().getName()}"
        script.echo "Describable cloud: ${describable.cloud}"
        script.echo "Describable label: ${describable.label}"
        
        return {
            script.echo "=== Inside closure execution ==="
            
            // Convert declarative agent configuration to slurmJobTemplate arguments
            def args = describable.asArgs
            script.echo "Args generated: ${args}"
            
            // Get or generate a label for this agent
            def agentLabel = describable.label ?: "slurm-${UUID.randomUUID().toString()}"
            args.label = agentLabel
            
            script.echo "Starting SLURM agent with label: ${agentLabel}"
            script.echo "Calling slurmJobTemplate step with args: ${args}"
            
            // Use slurmJobTemplate step to create the agent environment
            script.slurmJobTemplate(args) {
                script.echo "=== Inside slurmJobTemplate block ==="
                script.echo "Template created/added to cloud '${describable.cloud}' with label '${agentLabel}'"
                // Request a node with the specific label
                // This will trigger the SLURM cloud to provision an agent
                script.echo "Requesting node with label: ${agentLabel}"
                script.echo "This will now wait for a SLURM agent to come online with this label..."
                script.node(agentLabel) {
                    script.echo "=== Inside node block - SLURM agent successfully connected! ==="
                    script.echo "Running on node: ${script.env.NODE_NAME}"
                    
                    // Use doCheckout2 for declarative pipelines - this properly executes the body
                    CheckoutScript.doCheckout2(script, describable, describable.customWorkspace) {
                        script.echo "=== Executing pipeline body ==="
                        body.call()
                        script.echo "=== Pipeline body completed ==="
                    }
                }
            }
            script.echo "=== SLURM DECLARATIVE AGENT SCRIPT COMPLETED ==="
        }
    }
}
