package com.rishit.kv.router;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Consistent hash ring used to decide which node owns a key.
 *
 * <p>Nodes and keys are hashed onto the same circle of 64-bit numbers. A key
 * belongs to the first node found when walking clockwise from the key's hash.
 * When a node joins or leaves, only the keys between it and its neighbour
 * move. With "hash mod N", almost every key would move.
 *
 * <p>Each physical node is placed on the ring many times ("virtual nodes",
 * e.g. "node1#0", "node1#1", ...). With a single point per node the gaps
 * between nodes are random and one node could own half the keys. Many small
 * slices per node even out the load.
 *
 * <p>The ring is a TreeMap from hash to node. {@code ceilingEntry(hash)} finds
 * the next node clockwise in O(log n).
 */
public class ConsistentHashRing {

    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final Set<String> nodes = new LinkedHashSet<>();
    private final int virtualNodes;

    public ConsistentHashRing(int virtualNodes) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be at least 1");
        }
        this.virtualNodes = virtualNodes;
    }

    public synchronized void addNode(String node) {
        if (!nodes.add(node)) {
            return; // already in the ring
        }
        for (int i = 0; i < virtualNodes; i++) {
            ring.put(hash(node + "#" + i), node);
        }
    }

    public synchronized void removeNode(String node) {
        if (!nodes.remove(node)) {
            return;
        }
        for (int i = 0; i < virtualNodes; i++) {
            ring.remove(hash(node + "#" + i), node);
        }
    }

    /** Returns the node that owns the key: the first node clockwise from hash(key). */
    public synchronized String nodeFor(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("No nodes in the ring");
        }
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash(key));
        if (entry == null) {
            entry = ring.firstEntry(); // past the last point, wrap around to the start
        }
        return entry.getValue();
    }

    public synchronized List<String> nodes() {
        return List.copyOf(nodes);
    }

    /**
     * MD5 is used only because it spreads similar strings ("key1", "key2")
     * evenly. It is not used for security. String.hashCode() gives similar
     * strings similar hashes, which clusters them on the ring.
     */
    static long hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(UTF_8));
            return ByteBuffer.wrap(digest).getLong(); // first 8 of the 16 bytes
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is always available in the JDK", e);
        }
    }
}
