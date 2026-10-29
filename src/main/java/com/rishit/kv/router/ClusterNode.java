package com.rishit.kv.router;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * What the router knows about one storage node: its address, its status, and
 * the writes it missed while it was down ("hints").
 *
 * <pre>
 *        request fails / N failed health checks
 *   UP ------------------------------------------> DOWN
 *    ^                                              |
 *    |  all hints replayed                          | health check OK
 *    +------------------ RECOVERING &lt;---------------+
 *                            |
 *                            +--> DOWN if a replay fails
 * </pre>
 *
 * All methods are synchronized: request threads and the health checker use
 * the same object, and "check status + queue hint" must happen as one step.
 */
public class ClusterNode {

    private static final Logger LOG = Logger.getLogger(ClusterNode.class.getName());

    public enum Status { UP, DOWN, RECOVERING }

    private final String address;
    private final Deque<Write> hints = new ArrayDeque<>();
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

    public synchronized int pendingHints() {
        return hints.size();
    }

    /** Called when a request to the node failed. One failed request is enough. */
    public synchronized void markDown(String reason) {
        if (status != Status.DOWN) {
            status = Status.DOWN;
            LOG.warning("Node " + address + " marked DOWN: " + reason);
        }
    }

    /**
     * If the node is not UP, stores the write as a hint and returns true, so the
     * caller does not send it. Because the status check and the queueing happen
     * under the same lock as {@link #nextHintToReplay()}, a write can never be
     * queued after the replay finished. That write would be stuck in the queue
     * while the node is already UP.
     */
    public synchronized boolean queueIfNotUp(Write write) {
        if (status == Status.UP) {
            return false;
        }
        hints.addLast(write);
        return true;
    }

    /** A write to this node just failed: mark it down and keep the write for later. */
    public synchronized void markDownAndQueue(Write write, String reason) {
        markDown(reason);
        hints.addLast(write);
    }

    /**
     * Records the result of one health check. A node is marked down only after
     * {@code failureThreshold} failed checks in a row, so a single slow response
     * does not take it out of rotation.
     *
     * @return true if the node is reachable again and needs to recover
     */
    public synchronized boolean recordHealthCheck(boolean healthy, int failureThreshold) {
        if (healthy) {
            consecutiveFailures = 0;
            return status == Status.DOWN;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            markDown(consecutiveFailures + " health checks failed in a row");
        }
        return false;
    }

    /** DOWN -> RECOVERING. Returns false if the node was not DOWN. */
    public synchronized boolean startRecovery() {
        if (status != Status.DOWN) {
            return false;
        }
        status = Status.RECOVERING;
        return true;
    }

    /**
     * Returns the oldest hint without removing it, or null when recovery is over.
     * If no hints are left, the node is marked UP in the same synchronized step.
     * Also returns null if the node was marked DOWN again during recovery.
     */
    public synchronized Write nextHintToReplay() {
        if (status != Status.RECOVERING) {
            return null;
        }
        Write next = hints.peekFirst();
        if (next == null) {
            status = Status.UP;
            LOG.info("Node " + address + " is back UP");
        }
        return next;
    }

    /** Removes the oldest hint after it was successfully replayed. */
    public synchronized void hintReplayed() {
        hints.pollFirst();
    }
}
