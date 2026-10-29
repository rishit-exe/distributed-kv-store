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
    private static final int LOCK_STRIPES = 64;

    private final ConsistentHashRing ring;
    private final NodeClient client;
    private final int replicationFactor;
    private final int writeQuorum;

    /**
     * Two clients writing the same key at the same time could reach replica A
     * in the order (x, y) and replica B in the order (y, x), leaving the replicas
     * with different values forever. Holding a lock for the key while writing to
     * all replicas makes every replica see writes to that key in the same order.
     *
     * One lock per key would need unbounded memory, so keys share 64 locks
     * ("lock striping"). Writes to different stripes still run in parallel.
     */
    private final Object[] writeLocks = new Object[LOCK_STRIPES];

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
        for (int i = 0; i < LOCK_STRIPES; i++) {
            writeLocks[i] = new Object();
        }
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
        synchronized (lockFor(key)) {
            for (String node : replicasFor(key)) {
                try {
                    client.put(node, key, value);
                    acks++;
                } catch (IOException e) {
                    LOG.warning("Write to " + node + " failed: " + e.getMessage());
                }
            }
        }
        requireQuorum(acks);
        return acks;
    }

    /** Deletes from every replica. Returns true if any replica had the key. */
    public boolean delete(String key) throws ClusterUnavailableException {
        int acks = 0;
        boolean existed = false;
        synchronized (lockFor(key)) {
            for (String node : replicasFor(key)) {
                try {
                    existed |= client.delete(node, key);
                    acks++;
                } catch (IOException e) {
                    LOG.warning("Delete on " + node + " failed: " + e.getMessage());
                }
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

    private Object lockFor(String key) {
        return writeLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    private void requireQuorum(int acks) throws ClusterUnavailableException {
        if (acks < writeQuorum) {
            throw new ClusterUnavailableException(
                    "Only " + acks + " of " + writeQuorum + " required replicas confirmed the write");
        }
    }
}
