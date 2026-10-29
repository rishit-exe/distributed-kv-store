package com.rishit.kv.router;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Failure detector: every {@code interval} it calls GET /health on each node
 * and updates the node's status (heartbeat-style failure detection). When a
 * DOWN node answers again, it starts the node's recovery (hint replay).
 */
public class HealthChecker {

    private static final Logger LOG = Logger.getLogger(HealthChecker.class.getName());

    private final Coordinator coordinator;
    private final NodeClient client;
    private final int failureThreshold;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "health-checker");
        thread.setDaemon(true);
        return thread;
    });

    public HealthChecker(Coordinator coordinator, NodeClient client, int failureThreshold) {
        this.coordinator = coordinator;
        this.client = client;
        this.failureThreshold = failureThreshold;
    }

    public void start(Duration interval) {
        scheduler.scheduleWithFixedDelay(this::checkAll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /** Checks every node once. Public so tests can run a round without waiting. */
    public void checkAll() {
        // If a scheduled task throws, the executor silently stops running it.
        // Catch everything so one bad round does not stop failure detection.
        try {
            for (ClusterNode node : coordinator.members()) {
                boolean needsRecovery = node.recordHealthCheck(client.isHealthy(node.address()), failureThreshold);
                if (needsRecovery) {
                    coordinator.recover(node);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Health check round failed", e);
        }
    }
}
