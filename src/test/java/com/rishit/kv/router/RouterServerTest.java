package com.rishit.kv.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Talks to a router in front of 3 real nodes, without replication (N = 1). */
class RouterServerTest {

    private TestCluster cluster;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new TestCluster(3, 1, 1);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
    }

    @Test
    void putGetDeleteThroughRouter() throws Exception {
        assertEquals(200, cluster.send("PUT", "/kv/user1", "rishit").statusCode());
        assertEquals("rishit", cluster.send("GET", "/kv/user1", null).body());

        assertEquals(200, cluster.send("DELETE", "/kv/user1", null).statusCode());
        assertEquals(404, cluster.send("GET", "/kv/user1", null).statusCode());
    }

    @Test
    void keysAreSpreadAcrossNodes() throws Exception {
        for (int i = 0; i < 300; i++) {
            assertEquals(200, cluster.send("PUT", "/kv/key" + i, "v" + i).statusCode());
        }

        int total = 0;
        for (String address : cluster.addresses()) {
            int size = cluster.store(address).size();
            assertTrue(size > 0, "a node received no keys");
            total += size;
        }
        assertEquals(300, total, "each key should live on exactly one node");
    }

    @Test
    void invalidKeyReturns400() throws Exception {
        assertEquals(400, cluster.send("GET", "/kv/bad%2Fkey", null).statusCode());
    }

    @Test
    void returns503WhenOwningNodeIsDown() throws Exception {
        cluster.addresses().forEach(cluster::stopNode);
        assertEquals(503, cluster.send("GET", "/kv/user1", null).statusCode());
        assertEquals(503, cluster.send("PUT", "/kv/user1", "x").statusCode());
    }
}
