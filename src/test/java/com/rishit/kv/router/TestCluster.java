package com.rishit.kv.router;

import com.rishit.kv.node.NodeServer;
import com.rishit.kv.store.InMemoryKeyValueStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs real nodes and a router inside the test JVM on random ports.
 * A stopped node can be restarted on the same port with the same store, which
 * behaves like a real node restarting with its data on disk.
 */
class TestCluster implements AutoCloseable {

    static final int FAILURE_THRESHOLD = 3;

    final ConsistentHashRing ring = new ConsistentHashRing(100);
    final Coordinator coordinator;
    final RouterServer router;
    /** Not started on a timer. Tests call checkAll() to run one round on demand. */
    final HealthChecker healthChecker;

    private final Map<String, NodeServer> nodes = new LinkedHashMap<>();
    private final Map<String, InMemoryKeyValueStore> stores = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    TestCluster(int nodeCount, int replicationFactor, int writeQuorum) throws IOException {
        for (int i = 0; i < nodeCount; i++) {
            InMemoryKeyValueStore store = new InMemoryKeyValueStore();
            NodeServer node = new NodeServer(0, store, 4);
            node.start();
            String address = "localhost:" + node.getPort();
            nodes.put(address, node);
            stores.put(address, store);
            ring.addNode(address);
        }
        NodeClient client = new NodeClient(Duration.ofMillis(500));
        coordinator = new Coordinator(ring, client, replicationFactor, writeQuorum);
        healthChecker = new HealthChecker(coordinator, client, FAILURE_THRESHOLD);
        router = new RouterServer(0, coordinator, 8);
        router.start();
    }

    List<String> addresses() {
        return List.copyOf(nodes.keySet());
    }

    InMemoryKeyValueStore store(String address) {
        return stores.get(address);
    }

    void stopNode(String address) {
        nodes.get(address).stop();
    }

    void restartNode(String address) throws IOException {
        int port = Integer.parseInt(address.substring(address.indexOf(':') + 1));
        NodeServer node = new NodeServer(port, stores.get(address), 4);
        node.start();
        nodes.put(address, node);
    }

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + router.getPort() + path))
                .method(method, publisher)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        router.stop();
        nodes.values().forEach(NodeServer::stop);
    }
}
