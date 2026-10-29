package com.rishit.kv.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ClusterStatusTest {

    @Test
    void statusListsEveryNodeWithItsStats() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            cluster.send("PUT", "/kv/user1", "rishit");
            cluster.send("GET", "/kv/user1", null);

            HttpResponse<String> response = cluster.send("GET", "/cluster/status", null);
            assertEquals(200, response.statusCode());
            String json = response.body();

            assertTrue(json.contains("\"replicationFactor\":2"), json);
            assertTrue(json.contains("\"router\":{\"reads\":1,\"writes\":1,\"deletes\":0,\"failed\":0}"), json);
            for (String address : cluster.addresses()) {
                assertTrue(json.contains("{\"address\":\"" + address + "\",\"status\":\"UP\",\"pendingHints\":0,"
                        + "\"stats\":{\"keys\":"), json);
            }
        }
    }

    @Test
    void downNodeShowsHintsAndNoStats() throws Exception {
        try (TestCluster cluster = new TestCluster(3, 2, 1)) {
            String secondary = cluster.coordinator.replicasFor("user1").get(1);
            cluster.stopNode(secondary);
            cluster.send("PUT", "/kv/user1", "rishit");

            String json = cluster.send("GET", "/cluster/status", null).body();

            assertTrue(json.contains("{\"address\":\"" + secondary
                    + "\",\"status\":\"DOWN\",\"pendingHints\":1,\"stats\":null}"), json);
        }
    }
}
