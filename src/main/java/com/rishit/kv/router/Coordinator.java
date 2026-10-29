package com.rishit.kv.router;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Finds the replicas of a key on the {@link ConsistentHashRing} and talks to them.
 *
 * <ul>
 *   <li>Every key is stored on {@code replicationFactor} nodes (N).</li>
 *   <li>A write is sent to all N replicas. It succeeds if at least
 *       {@code writeQuorum} (W) of them confirm it.</li>
 *   <li>A read goes to the first replica that is UP and answers.</li>
 *   <li>Nodes that are not UP are skipped instead of waiting for them to time
 *       out. A failed request marks the node DOWN right away.</li>
 *   <li>Hinted handoff: a write that a replica could not receive is kept as a
 *       "hint" on its {@link ClusterNode}. When the node is reachable again,
 *       {@link #recover} replays the hints in order before the node is UP.
 *       So a node that is UP has every write, and reads from it are not stale.</li>
 * </ul>
 */
public class Coordinator {

    private static final Logger LOG = Logger.getLogger(Coordinator.class.getName());
    private static final int LOCK_STRIPES = 64;

    private final ConsistentHashRing ring;
    private final NodeClient client;
    private final int replicationFactor;
    private final int writeQuorum;
    private final Map<String, ClusterNode> members = new LinkedHashMap<>();

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
        for (String address : ring.nodes()) {
            members.put(address, new ClusterNode(address));
        }
        for (int i = 0; i < LOCK_STRIPES; i++) {
            writeLocks[i] = new Object();
        }
    }

    /** Returns the value from the first healthy replica that answers. */
    public Optional<String> get(String key) throws ClusterUnavailableException {
        for (ClusterNode node : replicaNodes(key)) {
            if (!node.isUp()) {
                continue;
            }
            try {
                return client.get(node.address(), key);
            } catch (IOException e) {
                node.markDown("read failed: " + describe(e));
            }
        }
        throw new ClusterUnavailableException("No replica of key '" + key + "' is reachable");
    }

    /** Writes to every replica. Returns how many replicas confirmed the write. */
    public int put(String key, String value) throws ClusterUnavailableException {
        return write(Write.put(key, value)).acks();
    }

    /** Deletes from every replica. Returns true if any replica had the key. */
    public boolean delete(String key) throws ClusterUnavailableException {
        return write(Write.delete(key)).existed();
    }

    /**
     * Replays the hints of a node that became reachable again, oldest first.
     * New writes for the node keep being queued while this runs, and the node
     * is marked UP only once the queue is empty. If a replay fails, the node
     * goes back to DOWN and keeps the remaining hints for the next attempt.
     */
    public void recover(ClusterNode node) {
        if (!node.startRecovery()) {
            return;
        }
        LOG.info("Recovering " + node.address() + ", " + node.pendingHints() + " hints to replay");
        Write hint;
        while ((hint = node.nextHintToReplay()) != null) {
            try {
                send(node.address(), hint);
                node.hintReplayed();
            } catch (IOException e) {
                node.markDown("hint replay failed: " + describe(e));
                return;
            }
        }
    }

    public List<String> replicasFor(String key) {
        return ring.nodesFor(key, replicationFactor);
    }

    public List<String> nodes() {
        return ring.nodes();
    }

    public Collection<ClusterNode> members() {
        return members.values();
    }

    public ClusterNode member(String address) {
        return members.get(address);
    }

    public int replicationFactor() {
        return replicationFactor;
    }

    public int writeQuorum() {
        return writeQuorum;
    }

    private WriteResult write(Write write) throws ClusterUnavailableException {
        int acks = 0;
        boolean existed = false;
        synchronized (lockFor(write.key())) {
            for (ClusterNode node : replicaNodes(write.key())) {
                if (node.queueIfNotUp(write)) {
                    continue; // node is down or still catching up, it gets this write later
                }
                try {
                    existed |= send(node.address(), write);
                    acks++;
                } catch (IOException e) {
                    node.markDownAndQueue(write, "write failed: " + describe(e));
                }
            }
        }
        requireQuorum(acks);
        return new WriteResult(acks, existed);
    }

    /** Sends one write to a node. Returns false only for a DELETE of a missing key. */
    private boolean send(String address, Write write) throws IOException {
        if (write.isDelete()) {
            return client.delete(address, write.key());
        }
        client.put(address, write.key(), write.value());
        return true;
    }

    private List<ClusterNode> replicaNodes(String key) {
        return replicasFor(key).stream().map(members::get).toList();
    }

    private Object lockFor(String key) {
        return writeLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    /**
     * Hints are kept even when the quorum is not reached, so the replicas still
     * converge. The client gets an error and can retry. PUT and DELETE are
     * idempotent, so a retry is safe.
     */
    private void requireQuorum(int acks) throws ClusterUnavailableException {
        if (acks < writeQuorum) {
            throw new ClusterUnavailableException(
                    "Only " + acks + " of " + writeQuorum + " required replicas confirmed the write");
        }
    }

    /** Some exceptions (e.g. ConnectException) have no message, so fall back to the type. */
    private static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private record WriteResult(int acks, boolean existed) {
    }
}
