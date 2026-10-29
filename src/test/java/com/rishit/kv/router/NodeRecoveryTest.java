package com.rishit.kv.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rishit.kv.router.ClusterNode.Status;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NodeRecoveryTest {

    @Test
    void restartedNodeCatchesUpOnWritesItMissed() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            String primary = cluster.coordinator.replicasFor("user1").get(0);
            String secondary = cluster.coordinator.replicasFor("user1").get(1);

            cluster.send("PUT", "/kv/user1", "v1");

            // Secondary goes down and misses an update and a delete.
            cluster.stopNode(secondary);
            cluster.send("PUT", "/kv/user1", "v2");
            cluster.send("PUT", "/kv/user2", "temp");
            cluster.send("DELETE", "/kv/user2", null);

            // Secondary comes back with its old data, the health checker notices.
            cluster.restartNode(secondary);
            cluster.healthChecker.checkAll();
            assertEquals(Status.UP, cluster.coordinator.member(secondary).status());

            // Now lose the primary: the read must come from the secondary and be up to date.
            cluster.coordinator.member(primary).markDown("test");
            assertEquals("v2", cluster.send("GET", "/kv/user1", null).body());
            assertEquals(Optional.empty(), cluster.store(secondary).get("user2"));
        }
    }

    @Test
    void writesToDownNodeAreQueuedAndReplayedInOrder() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            String secondary = cluster.coordinator.replicasFor("counter").get(1);
            ClusterNode node = cluster.coordinator.member(secondary);
            node.markDown("test"); // process still runs, router just treats it as down

            for (int i = 1; i <= 5; i++) {
                cluster.send("PUT", "/kv/counter", String.valueOf(i));
            }
            assertEquals(5, node.pendingHints());
            assertEquals(Optional.empty(), cluster.store(secondary).get("counter"));

            cluster.healthChecker.checkAll();

            assertEquals(Status.UP, node.status());
            assertEquals(0, node.pendingHints());
            assertEquals(Optional.of("5"), cluster.store(secondary).get("counter"));
        }
    }

    @Test
    void failedReplayKeepsHintsAndNodeStaysDown() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            String secondary = cluster.coordinator.replicasFor("user1").get(1);
            ClusterNode node = cluster.coordinator.member(secondary);
            node.markDown("test");
            cluster.send("PUT", "/kv/user1", "v1");

            // The node dies for real before the router can replay to it.
            cluster.stopNode(secondary);
            cluster.healthChecker.checkAll();
            assertEquals(Status.DOWN, node.status());
            assertEquals(1, node.pendingHints());

            cluster.restartNode(secondary);
            cluster.healthChecker.checkAll();
            assertEquals(Status.UP, node.status());
            assertEquals(Optional.of("v1"), cluster.store(secondary).get("user1"));
        }
    }
}
