package com.rishit.kv.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsistentHashRingTest {

    private static final int KEYS = 30_000;

    @Test
    void emptyRingThrows() {
        ConsistentHashRing ring = new ConsistentHashRing(100);
        assertThrows(IllegalStateException.class, () -> ring.nodeFor("key"));
    }

    @Test
    void sameKeyAlwaysMapsToSameNode() {
        ConsistentHashRing ring = ringWith("node1", "node2", "node3");
        String owner = ring.nodeFor("user42");
        for (int i = 0; i < 10; i++) {
            assertEquals(owner, ring.nodeFor("user42"));
        }
    }

    @Test
    void keysAreSpreadEvenlyWithVirtualNodes() {
        ConsistentHashRing ring = ringWith("node1", "node2", "node3");
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < KEYS; i++) {
            counts.merge(ring.nodeFor("key" + i), 1, Integer::sum);
        }

        // A perfect split would be 33.3% each.
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            double share = entry.getValue() / (double) KEYS;
            assertTrue(share > 0.25 && share < 0.42, entry.getKey() + " owns " + share);
        }
    }

    @Test
    void addingANodeOnlyMovesKeysToTheNewNode() {
        ConsistentHashRing ring = ringWith("node1", "node2", "node3");
        Map<String, String> before = ownership(ring);

        ring.addNode("node4");

        int moved = 0;
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String newOwner = ring.nodeFor(entry.getKey());
            if (!newOwner.equals(entry.getValue())) {
                moved++;
                assertEquals("node4", newOwner, "keys should only move to the new node");
            }
        }
        // Ideally 1/4 of the keys move. With "hash mod N" it would be about 3/4.
        double movedShare = moved / (double) KEYS;
        assertTrue(movedShare > 0.15 && movedShare < 0.35, "moved " + movedShare);
    }

    @Test
    void removingANodeOnlyMovesThatNodesKeys() {
        ConsistentHashRing ring = ringWith("node1", "node2", "node3");
        Map<String, String> before = ownership(ring);

        ring.removeNode("node2");

        for (Map.Entry<String, String> entry : before.entrySet()) {
            String newOwner = ring.nodeFor(entry.getKey());
            if (!entry.getValue().equals("node2")) {
                assertEquals(entry.getValue(), newOwner, "key not owned by node2 should stay put");
            }
            assertTrue(!newOwner.equals("node2"));
        }
    }

    private static ConsistentHashRing ringWith(String... nodes) {
        ConsistentHashRing ring = new ConsistentHashRing(100);
        for (String node : nodes) {
            ring.addNode(node);
        }
        return ring;
    }

    private static Map<String, String> ownership(ConsistentHashRing ring) {
        Map<String, String> owners = new HashMap<>();
        for (int i = 0; i < KEYS; i++) {
            owners.put("key" + i, ring.nodeFor("key" + i));
        }
        return owners;
    }
}
