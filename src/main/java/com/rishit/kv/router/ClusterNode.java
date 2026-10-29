package com.rishit.kv.router;

import java.util.logging.Logger;

/**
 * What the router knows about one storage node: its address and whether it is
 * currently considered up or down. All methods are synchronized because
 * request threads and the health checker update the same object.
 */
public class ClusterNode {

    private static final Logger LOG = Logger.getLogger(ClusterNode.class.getName());

    public enum Status { UP, DOWN }

    private final String address;
    private Status status = Status.UP;
    private int consecutiveFailures;

    public ClusterNode(String address) {
        this.address = address;
    }

    public String address() {
        return address;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized boolean isUp() {
        return status == Status.UP;
    }

    /** Called when a request to the node failed. One failed request is enough. */
    public synchronized void markDown(String reason) {
        if (status != Status.DOWN) {
            status = Status.DOWN;
            LOG.warning("Node " + address + " marked DOWN: " + reason);
        }
    }

    /**
     * Records the result of one health check. A node is marked down only after
     * {@code failureThreshold} failed checks in a row, so a single slow response
     * does not take it out of rotation. One successful check brings it back.
     */
    public synchronized void recordHealthCheck(boolean healthy, int failureThreshold) {
        if (healthy) {
            consecutiveFailures = 0;
            if (status == Status.DOWN) {
                status = Status.UP;
                LOG.info("Node " + address + " is back UP");
            }
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            markDown(consecutiveFailures + " health checks failed in a row");
        }
    }
}
