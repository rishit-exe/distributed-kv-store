package com.rishit.kv.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 3 nodes, every key stored on 2 of them. */
class ReplicationTest {

    @Test
    void everyKeyIsStoredOnExactlyTwoNodes() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            for (int i = 0; i < 100; i++) {
                cluster.send("PUT", "/kv/key" + i, "v" + i);
            }

            for (int i = 0; i < 100; i++) {
                String key = "key" + i;
                List<String> replicas = cluster.coordinator.replicasFor(key);
                for (String address : cluster.addresses()) {
                    Optional<String> value = cluster.store(address).get(key);
                    if (replicas.contains(address)) {
                        assertEquals(Optional.of("v" + i), value, key + " missing on replica " + address);
                    } else {
                        assertEquals(Optional.empty(), value, key + " should not be on " + address);
                    }
                }
            }
        }
    }

    @Test
    void readStillWorksWhenPrimaryIsDown() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            cluster.send("PUT", "/kv/user1", "rishit");

            String primary = cluster.coordinator.replicasFor("user1").get(0);
            cluster.stopNode(primary);

            HttpResponse<String> response = cluster.send("GET", "/kv/user1", null);
            assertEquals(200, response.statusCode());
            assertEquals("rishit", response.body());
        }
    }

    @Test
    void writeSucceedsWithOneReplicaDownWhenQuorumIsOne() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            cluster.stopNode(cluster.coordinator.replicasFor("user1").get(1));

            HttpResponse<String> response = cluster.send("PUT", "/kv/user1", "rishit");
            assertEquals(200, response.statusCode());
            assertEquals("1", response.headers().firstValue("X-Replicas-Acked").orElseThrow());
        }
    }

    @Test
    void writeFailsWhenQuorumCannotBeReached() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 2)) {
            cluster.stopNode(cluster.coordinator.replicasFor("user1").get(1));

            assertEquals(503, cluster.send("PUT", "/kv/user1", "rishit").statusCode());
        }
    }

    @Test
    void deleteRemovesKeyFromAllReplicas() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            cluster.send("PUT", "/kv/user1", "rishit");
            assertEquals(200, cluster.send("DELETE", "/kv/user1", null).statusCode());

            for (String address : cluster.addresses()) {
                assertFalse(cluster.store(address).get("user1").isPresent());
            }
        }
    }

    @Test
    void invalidSettingsAreRejected() {
        ConsistentHashRing ring = new ConsistentHashRing(10);
        ring.addNode("a:1");
        ring.addNode("b:1");
        NodeClient client = new NodeClient(java.time.Duration.ofMillis(100));

        assertThrows(IllegalArgumentException.class, () -> new Coordinator(ring, client, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> new Coordinator(ring, client, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new Coordinator(ring, client, 2, 0));
    }
}
