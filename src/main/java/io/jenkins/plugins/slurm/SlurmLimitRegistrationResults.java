package io.jenkins.plugins.slurm;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks successful {@link SlurmProvisioningLimits} reservations during a single {@link SlurmCloud#provision}
 * call and supports per-slot release when async agent creation fails.
 */
final class SlurmLimitRegistrationResults {
    private static final Logger LOGGER = Logger.getLogger(SlurmLimitRegistrationResults.class.getName());

    private final SlurmCloud cloud;
    private final List<Registration> registrations = new ArrayList<>();

    SlurmLimitRegistrationResults(@NonNull SlurmCloud cloud) {
        this.cloud = cloud;
    }

    /**
     * @return a registration handle when capacity was reserved, otherwise {@code null}
     */
    Registration register(@NonNull SlurmJobTemplate template, int numExecutors) {
        boolean success =
                SlurmProvisioningLimits.get().register(cloud, template, numExecutors);
        Registration registration = new Registration(success, template, numExecutors);
        registrations.add(registration);
        return success ? registration : null;
    }

    void unregisterAll() {
        registrations.forEach(registration -> registration.release(cloud));
        registrations.clear();
    }

    static final class Registration {
        private final boolean success;
        private final SlurmJobTemplate template;
        private final int numExecutors;
        private volatile boolean released;

        Registration(boolean success, @NonNull SlurmJobTemplate template, int numExecutors) {
            this.success = success;
            this.template = template;
            this.numExecutors = numExecutors;
        }

        void release(@NonNull SlurmCloud cloud) {
            if (!success || released) {
                return;
            }
            released = true;
            LOGGER.log(
                    Level.FINEST,
                    () -> "Releasing reserved slot for template " + template.getName() + " on cloud "
                            + cloud.name);
            SlurmProvisioningLimits.get().unregister(cloud, template, numExecutors);
        }
    }
}
