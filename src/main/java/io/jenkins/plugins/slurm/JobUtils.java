/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;

/**
 * Utility methods for Slurm job and agent management.
 *
 * Similar to Kubernetes plugin's PodUtils, this provides helper methods for
 * managing the lifecycle of Slurm jobs and their associated Jenkins agents,
 * particularly for cancelling queue items when agent provisioning fails.
 */
public final class JobUtils {
    private JobUtils() {}

    private static final Logger LOGGER = Logger.getLogger(JobUtils.class.getName());

    /**
     * Finds the build console {@link TaskListener} for a queued pipeline {@code node('label')} block
     * waiting on the given Jenkins label.
     *
     * <p>When provisioning via a static cloud template (no {@code slurmJobTemplate()} step), the
     * template has no listener. Scanning the queue for a matching {@link ExecutorStepExecution.PlaceholderTask}
     * lets provisioning logs reach the build console before the agent is online.
     *
     * @param labelString Jenkins label the agent is being provisioned for
     * @return the build listener, or {@link TaskListener#NULL} if none is found
     */
    @NonNull
    public static TaskListener findRunListenerForLabel(@NonNull String labelString) {
        if (labelString.isBlank()) {
            return TaskListener.NULL;
        }

        for (Queue.Item item : Jenkins.get().getQueue().getItems()) {
            Label assignedLabel = item.getAssignedLabel();
            if (assignedLabel == null) {
                continue;
            }
            if (!labelString.equals(assignedLabel.getName())) {
                continue;
            }

            TaskListener listener = getListenerFromQueueItem(item);
            if (listener != null) {
                LOGGER.log(
                        Level.FINE,
                        () -> "Found build listener for label '" + labelString + "' from queue item: "
                                + item.task.getDisplayName());
                return listener;
            }
        }

        return TaskListener.NULL;
    }

    @CheckForNull
    private static TaskListener getListenerFromQueueItem(Queue.Item item) {
        Queue.Task task = item.task;
        if (!(task instanceof ExecutorStepExecution.PlaceholderTask)) {
            return null;
        }

        ExecutorStepExecution.PlaceholderTask placeholderTask = (ExecutorStepExecution.PlaceholderTask) task;
        Run<?, ?> run = placeholderTask.runForDisplay();
        if (run == null) {
            return null;
        }

        if (run instanceof FlowExecutionOwner.Executable) {
            FlowExecutionOwner owner = ((FlowExecutionOwner.Executable) run).asFlowExecutionOwner();
            if (owner != null) {
                try {
                    return owner.getListener();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Could not get listener from queue item " + item, e);
                }
            }
        }
        return null;
    }

    /**
     * Cancel queue items matching the given agent based on label matching.
     *
     * <p>When a Slurm job fails to launch an agent (e.g., bad image, network issues,
     * startup script errors), we need to cancel the corresponding Jenkins queue item
     * to prevent Jenkins from continuously provisioning new agents in an infinite loop.
     *
     * <p>This method cancels queue items that:
     * <ul>
     *   <li>Have an assigned label matching the agent's label</li>
     * </ul>
     *
     * <p>This is based on the Kubernetes plugin's PodUtils.cancelQueueItemFor pattern
     * but simplified for Slurm's use case where we don't have pod annotations with
     * runUrl. We rely on label matching which is sufficient for most scenarios.
     *
     * @param agent The Slurm agent whose queue item should be cancelled
     * @param reason The reason the item is being cancelled (for logging)
     */
    public static void cancelQueueItemFor(@NonNull SlurmAgent agent, @CheckForNull String reason) {
        String labelString = agent.getLabelString();
        if (labelString == null || labelString.trim().isEmpty()) {
            LOGGER.log(Level.FINE, () -> "Agent " + agent.getNodeName() + " has no label, cannot cancel queue item");
            return;
        }

        String agentName = agent.getNodeName();
        cancelQueueItemFor(labelString, reason, agentName);
    }

    /**
     * Cancel queue items for the given label.
     *
     * <p>This is a lower-level method that can be called when you have the label string
     * but not necessarily the agent object (e.g., during early provisioning failures).
     *
     * @param labelString The label string to match against queue items
     * @param reason The reason the item is being cancelled (for logging)
     * @param agentName Optional agent name for better logging
     */
    public static void cancelQueueItemFor(
            @NonNull String labelString, @CheckForNull String reason, @CheckForNull String agentName) {

        Queue queue = Jenkins.get().getQueue();

        // Find and cancel matching queue item
        Arrays.stream(queue.getItems())
                .filter(item -> {
                    Label assignedLabel = item.getAssignedLabel();
                    if (assignedLabel == null) {
                        return false;
                    }

                    // Match if the assigned label name equals our label string
                    // This handles both single labels and multi-label scenarios
                    String assignedLabelName = assignedLabel.getName();
                    return assignedLabelName != null && assignedLabelName.equals(labelString);
                })
                .findFirst()
                .ifPresentOrElse(
                        item -> {
                            String displayName = item.task != null ? item.task.getDisplayName() : "unknown";
                            LOGGER.log(Level.INFO, () -> {
                                StringBuilder msg = new StringBuilder();
                                msg.append("Cancelling queue item: \"")
                                        .append(displayName)
                                        .append("\"");
                                if (agentName != null) {
                                    msg.append(" (agent: ").append(agentName).append(")");
                                }
                                if (!StringUtils.isBlank(reason)) {
                                    msg.append(" - Reason: ").append(reason);
                                }
                                return msg.toString();
                            });

                            queue.cancel(item);
                        },
                        () -> {
                            if (agentName != null) {
                                LOGGER.log(
                                        Level.FINE,
                                        () -> "No queue item found for agent " + agentName + " with label '"
                                                + labelString + "'");
                            } else {
                                LOGGER.log(Level.FINE, () -> "No queue item found with label '" + labelString + "'");
                            }
                        });
    }
}
