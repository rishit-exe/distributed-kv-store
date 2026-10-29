package com.rishit.kv.router;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Finds the replicas of a key on the {@link ConsistentHashRing} and talks to them.
 *
 * <ul>
 *   <li>Every key is stored on {@code replicationFactor} nodes (N).</li>
 *   <li>A write is sent to all N replicas. It succeeds if at least
 *       {@code writeQuorum} (W) of them confirm it.</li>
 *   <li>A read goes to the first replica that answers, so one node being down
 *       does not make its keys unreadable.</li>
 * </ul>
 */
public class Coordinator {

    private static final Logger LOG = Logger.getLogger(Coordinator.class.getName());

    private final ConsistentHashRing ring;
    private final NodeClient client;
    private final int replicationFactor;
    private final int writeQuorum;

    public Coordinator(ConsistentHashRing ring, NodeClient client, int replicationFactor, int writeQuorum) {
        int nodeCount = ring.nodes().size();
        if (replicationFactor < 1 || replicationFactor > nodeCount) {
            throw new IllegalArgumentException(
                    "replicationFactor must be between 1 and the number of nodes (" + nodeCount + ")");
        }
        if (writeQuorum < 1 || writeQuorum > replicationFactor) {
            throw new IllegalArgumentException("writeQuorum must be between 1 and replicationFactor");
        }
        this.ring = ring;
        this.client = client;
        this.replicationFactor = replicationFactor;
        this.writeQuorum = writeQuorum;
    }

    /** Returns the value from the first replica that answers. */
    public Optional<String> get(String key) throws ClusterUnavailableException {
        for (String node : replicasFor(key)) {
            try {
                return client.get(node, key);
            } catch (IOException e) {
                LOG.warning("Read from " + node + " failed, trying next replica: " + e.getMessage());
            }
        }
        throw new ClusterUnavailableException("No replica of key '" + key + "' is reachable");
    }

    /** Writes to every replica. Returns how many replicas confirmed the write. */
    public int put(String key, String value) throws ClusterUnavailableException {
        int acks = 0;
        for (String node : replicasFor(key)) {
            try {
                client.put(node, key, value);
                acks++;
            } catch (IOException e) {
                LOG.warning("Write to " + node + " failed: " + e.getMessage());
            }
        }
        requireQuorum(acks);
        return acks;
    }

    /** Deletes from every replica. Returns true if any replica had the key. */
    public boolean delete(String key) throws ClusterUnavailableException {
        int acks = 0;
        boolean existed = false;
        for (String node : replicasFor(key)) {
            try {
                existed |= client.delete(node, key);
                acks++;
            } catch (IOException e) {
                LOG.warning("Delete on " + node + " failed: " + e.getMessage());
            }
        }
        requireQuorum(acks);
        return existed;
    }

    public List<String> replicasFor(String key) {
        return ring.nodesFor(key, replicationFactor);
    }

    public List<String> nodes() {
        return ring.nodes();
    }

    public int replicationFactor() {
        return replicationFactor;
    }

    public int writeQuorum() {
        return writeQuorum;
    }

    private void requireQuorum(int acks) throws ClusterUnavailableException {
        if (acks < writeQuorum) {
            throw new ClusterUnavailableException(
                    "Only " + acks + " of " + writeQuorum + " required replicas confirmed the write");
        }
    }
}
