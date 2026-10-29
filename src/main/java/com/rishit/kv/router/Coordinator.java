package com.rishit.kv.router;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Decides which node owns a key (using the {@link ConsistentHashRing}) and
 * forwards the request to it.
 */
public class Coordinator {

    private final ConsistentHashRing ring;
    private final NodeClient client;

    public Coordinator(ConsistentHashRing ring, NodeClient client) {
        if (ring.nodes().isEmpty()) {
            throw new IllegalArgumentException("At least one node is required");
        }
        this.ring = ring;
        this.client = client;
    }

    public Optional<String> get(String key) throws IOException {
        return client.get(ring.nodeFor(key), key);
    }

    public void put(String key, String value) throws IOException {
        client.put(ring.nodeFor(key), key, value);
    }

    public boolean delete(String key) throws IOException {
        return client.delete(ring.nodeFor(key), key);
    }

    public List<String> nodes() {
        return ring.nodes();
    }
}
