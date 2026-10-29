package com.rishit.kv.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rishit.kv.router.ClusterNode.Status;
import org.junit.jupiter.api.Test;

class FailureDetectionTest {

    @Test
    void nodeIsMarkedDownOnlyAfterThresholdFailedChecks() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            String address = cluster.addresses().get(0);
            ClusterNode node = cluster.coordinator.member(address);
            cluster.stopNode(address);

            for (int i = 1; i < TestCluster.FAILURE_THRESHOLD; i++) {
                cluster.healthChecker.checkAll();
                assertEquals(Status.UP, node.status(), "should still be UP after " + i + " failed checks");
            }
            cluster.healthChecker.checkAll();
            assertEquals(Status.DOWN, node.status());
        }
    }

    @Test
    void nodeComesBackUpAfterSuccessfulCheck() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            String address = cluster.addresses().get(0);
            ClusterNode node = cluster.coordinator.member(address);
            node.markDown("test");

            cluster.healthChecker.checkAll();

            assertEquals(Status.UP, node.status());
        }
    }

    @Test
    void failedWriteMarksNodeDownImmediately() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            String secondary = cluster.coordinator.replicasFor("user1").get(1);
            cluster.stopNode(secondary);

            cluster.send("PUT", "/kv/user1", "rishit");

            assertEquals(Status.DOWN, cluster.coordinator.member(secondary).status());
        }
    }

    @Test
    void downNodeIsSkippedForReadsAndWrites() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            String primary = cluster.coordinator.replicasFor("user1").get(0);
            cluster.coordinator.member(primary).markDown("test");

            // The node process is still running, but the router must not use it.
            assertEquals("1", cluster.send("PUT", "/kv/user1", "rishit")
                    .headers().firstValue("X-Replicas-Acked").orElseThrow());
            assertEquals(0, cluster.store(primary).size());
            assertEquals("rishit", cluster.send("GET", "/kv/user1", null).body());
        }
    }
}
