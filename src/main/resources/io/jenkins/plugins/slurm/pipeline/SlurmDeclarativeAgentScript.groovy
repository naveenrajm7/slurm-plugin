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
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentScript2
import org.jenkinsci.plugins.workflow.cps.CpsScript

/**
 * Script execution for Slurm declarative agents.
 * 
 * This script:
 * 1. Creates a Slurm job template step with the specified configuration
 * 2. Launches a Slurm agent within that template
 * 3. Performs SCM checkout if needed
 * 4. Executes the pipeline body
 * 5. Cleans up the Slurm job
 */
public class SlurmDeclarativeAgentScript extends DeclarativeAgentScript2<SlurmDeclarativeAgent> {
    
    public SlurmDeclarativeAgentScript(CpsScript s, SlurmDeclarativeAgent a) {
        super(s, a)
    }

    @Override
    public void run(Closure body) {
        // Handle JSON file loading (similar to Kubernetes yamlFile)
        if ((describable.jsonFile != null) && (describable.hasScmContext(script))) {
            describable.json = script.readTrusted(describable.jsonFile)
        }
        
        // Convert declarative agent configuration to slurmJobTemplate arguments.
        // args.label is already a unique per-invocation label (set by
        // SlurmDeclarativeAgent.getLabel() which appends a nanoTime suffix).
        // Do NOT overwrite it here — two concurrent builds with the same
        // user-specified label must get different unique labels so they each
        // get their own Slurm agent.
        def args = describable.asArgs

        // Use slurmJobTemplate step to create the agent environment
        script.slurmJobTemplate(args) {
            // Request a node with the unique label baked into args.
            // The template registered by slurmJobTemplate uses this same label,
            // so the provisioned agent will match exactly this build's request.
            script.node(args.label) {
                // Use doCheckout2 for declarative pipelines - this properly executes the body
                CheckoutScript.doCheckout2(script, describable, describable.customWorkspace) {
                    body.call()
                }
            }
        }
    }
}
