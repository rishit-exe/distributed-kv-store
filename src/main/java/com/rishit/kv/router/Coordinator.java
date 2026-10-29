package com.rishit.kv.router;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Decides which node owns a key and forwards the request to it.
 *
 * Placement is "hash(key) mod number of nodes". This is simple, but when a
 * node is added or removed almost every key maps to a different node.
 */
public class Coordinator {

    private final List<String> nodes;
    private final NodeClient client;

    public Coordinator(List<String> nodes, NodeClient client) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("At least one node is required");
        }
        this.nodes = List.copyOf(nodes);
        this.client = client;
    }

    public Optional<String> get(String key) throws IOException {
        return client.get(nodeFor(key), key);
    }

    public void put(String key, String value) throws IOException {
        client.put(nodeFor(key), key, value);
    }

    public boolean delete(String key) throws IOException {
        return client.delete(nodeFor(key), key);
    }

    public List<String> nodes() {
        return nodes;
    }

    String nodeFor(String key) {
        return nodes.get(Math.floorMod(key.hashCode(), nodes.size()));
    }
}
