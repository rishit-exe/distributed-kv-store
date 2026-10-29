package com.rishit.kv.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    void concurrentWritesToSameKeysLeaveReplicasIdentical() throws Exception {
        int threads = 8;
        int keys = 20;
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int threadId = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < 20; round++) {
                        for (int k = 0; k < keys; k++) {
                            cluster.send("PUT", "/kv/hot" + k, "t" + threadId + "-r" + round);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();

            for (int k = 0; k < keys; k++) {
                List<String> replicas = cluster.coordinator.replicasFor("hot" + k);
                assertEquals(cluster.store(replicas.get(0)).get("hot" + k),
                        cluster.store(replicas.get(1)).get("hot" + k),
                        "replicas of hot" + k + " diverged");
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
