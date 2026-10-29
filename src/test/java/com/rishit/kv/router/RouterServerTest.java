package com.rishit.kv.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rishit.kv.node.NodeServer;
import com.rishit.kv.store.InMemoryKeyValueStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Starts 3 real nodes and a router on random ports and talks to the router over HTTP. */
class RouterServerTest {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final List<NodeServer> nodeServers = new ArrayList<>();
    private final List<InMemoryKeyValueStore> stores = new ArrayList<>();
    private RouterServer router;
    private String routerUrl;

    @BeforeEach
    void setUp() throws Exception {
        ConsistentHashRing ring = new ConsistentHashRing(100);
        for (int i = 0; i < 3; i++) {
            InMemoryKeyValueStore store = new InMemoryKeyValueStore();
            NodeServer node = new NodeServer(0, store, 4);
            node.start();
            stores.add(store);
            nodeServers.add(node);
            ring.addNode("localhost:" + node.getPort());
        }
        Coordinator coordinator = new Coordinator(ring, new NodeClient(Duration.ofMillis(500)));
        router = new RouterServer(0, coordinator, 8);
        router.start();
        routerUrl = "http://localhost:" + router.getPort();
    }

    @AfterEach
    void tearDown() {
        router.stop();
        nodeServers.forEach(NodeServer::stop);
    }

    @Test
    void putGetDeleteThroughRouter() throws Exception {
        assertEquals(200, send("PUT", "/kv/user1", "rishit").statusCode());
        assertEquals("rishit", send("GET", "/kv/user1", null).body());

        assertEquals(200, send("DELETE", "/kv/user1", null).statusCode());
        assertEquals(404, send("GET", "/kv/user1", null).statusCode());
    }

    @Test
    void keysAreSpreadAcrossNodes() throws Exception {
        for (int i = 0; i < 300; i++) {
            assertEquals(200, send("PUT", "/kv/key" + i, "v" + i).statusCode());
        }

        int total = 0;
        for (InMemoryKeyValueStore store : stores) {
            assertTrue(store.size() > 0, "a node received no keys");
            total += store.size();
        }
        assertEquals(300, total, "each key should live on exactly one node");
    }

    @Test
    void returns503WhenOwningNodeIsDown() throws Exception {
        nodeServers.forEach(NodeServer::stop);
        assertEquals(503, send("GET", "/kv/user1", null).statusCode());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(routerUrl + path))
                .method(method, publisher)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
