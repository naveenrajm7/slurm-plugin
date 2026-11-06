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
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
     * Queue items already paired with an agent that is still waiting to connect.
     * One build must not be claimed by two agents, and one agent's abort must not
     * be treated as the end of a different build waiting on the same label.
     */
    private static final Map<Long, String> PROVISIONING_CLAIMS = new ConcurrentHashMap<>();

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
            if (!labelMatches(labelString, assignedLabel)) {
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

    /**
     * Returns true when a template/node label string satisfies a queued pipeline label expression.
     */
    static boolean labelMatches(@NonNull String templateLabelString, @NonNull Label assignedLabel) {
        if (templateLabelString.equals(assignedLabel.getName())) {
            return true;
        }
        return assignedLabel.matches(new java.util.LinkedHashSet<>(Label.parse(templateLabelString.trim())));
    }

    /**
     * Build console for {@code run}, when it is a pipeline execution.
     *
     * <p>Provisioning uses this so each claimed build receives its own status lines. A label scan
     * can hand two agents the same console when two builds are waiting.
     */
    @CheckForNull
    static TaskListener listenerFor(@CheckForNull Run<?, ?> run) {
        if (!(run instanceof FlowExecutionOwner.Executable executable)) {
            return null;
        }
        FlowExecutionOwner owner = executable.asFlowExecutionOwner();
        if (owner == null) {
            return null;
        }
        try {
            return owner.getListener();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not get listener from run " + run.getExternalizableId(), e);
            return null;
        }
    }

    @CheckForNull
    private static TaskListener getListenerFromQueueItem(Queue.Item item) {
        if (!(item.task instanceof ExecutorStepExecution.PlaceholderTask placeholder)) {
            return null;
        }
        return listenerFor(placeholder.runForDisplay());
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

                    // Match template labels against the queue item's assigned label expression
                    String assignedLabelName = assignedLabel.getName();
                    return assignedLabelName != null && labelMatches(labelString, assignedLabel);
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

    /**
     * Pairs {@code agentName} with one queued build for {@code provisionLabel}.
     *
     * <p>The oldest unclaimed item is taken so two agents planned for the same label watch
     * different builds. Returns null when nothing is waiting; the launcher then keeps the
     * agent-timeout path.
     */
    @CheckForNull
    static ProvisioningOwner claimProvisioningOwner(
            @CheckForNull Label provisionLabel, @NonNull String templateLabelString, @NonNull String agentName) {
        if (provisionLabel == null && templateLabelString.isBlank()) {
            return null;
        }

        synchronized (PROVISIONING_CLAIMS) {
            Queue.Item[] items = Jenkins.get().getQueue().getItems();
            Arrays.sort(items, Comparator.comparingLong(Queue.Item::getId));
            for (Queue.Item item : items) {
                if (PROVISIONING_CLAIMS.containsKey(item.getId())
                        || !itemMatches(item, provisionLabel, templateLabelString)) {
                    continue;
                }
                PROVISIONING_CLAIMS.put(item.getId(), agentName);
                Run<?, ?> run = runForQueueItem(item);
                LOGGER.log(
                        Level.FINE,
                        () -> "Claimed queue item " + item.getId() + " for agent " + agentName
                                + (run != null ? " (run " + run.getExternalizableId() + ")" : ""));
                return new ProvisioningOwner(item.getId(), run);
            }
        }
        return null;
    }

    /**
     * The claimed build is no longer waiting for this agent.
     *
     * <p>Aborting a pipeline cancels the {@code node} placeholder while the launcher thread is
     * still blocked in {@code waitForAgentConnection}. A missing queue item, or a run that is no
     * longer building, means this agent must not stay online for a later build. A non-null result
     * is not enough: a pipeline can be marked UNSTABLE or FAILURE while a later stage is still
     * waiting for this agent.
     */
    static boolean provisioningOwnerReleased(long queueItemId, @CheckForNull Run<?, ?> run) {
        if (Jenkins.get().getQueue().getItem(queueItemId) == null) {
            return true;
        }
        return run != null && !run.isBuilding();
    }

    static void releaseProvisioningClaim(long queueItemId) {
        PROVISIONING_CLAIMS.remove(queueItemId);
    }

    static void clearProvisioningClaimsForTest() {
        PROVISIONING_CLAIMS.clear();
    }

    private static boolean itemMatches(
            Queue.Item item, @CheckForNull Label provisionLabel, @NonNull String templateLabelString) {
        Label assigned = item.getAssignedLabel();
        if (assigned == null) {
            return false;
        }
        if (provisionLabel != null) {
            return provisionLabel.equals(assigned);
        }
        return !templateLabelString.isBlank() && labelMatches(templateLabelString, assigned);
    }

    @CheckForNull
    private static Run<?, ?> runForQueueItem(Queue.Item item) {
        if (item.task instanceof ExecutorStepExecution.PlaceholderTask placeholder) {
            return placeholder.runForDisplay();
        }
        return null;
    }

    /** A queued build paired with one agent for the pre-connect wait. */
    static final class ProvisioningOwner {
        final long queueItemId;

        @CheckForNull
        final Run<?, ?> run;

        ProvisioningOwner(long queueItemId, @CheckForNull Run<?, ?> run) {
            this.queueItemId = queueItemId;
            this.run = run;
        }
    }
}
