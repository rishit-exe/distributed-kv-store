package com.rishit.kv.router;

import static com.rishit.kv.common.HttpUtils.sendText;

import com.rishit.kv.common.HttpUtils;
import com.rishit.kv.common.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Public entry point of the cluster. Clients only talk to the router, using the
 * same API as a single node:
 *
 * <pre>
 * GET    /kv/{key}
 * PUT    /kv/{key}
 * DELETE /kv/{key}
 * GET    /health
 * GET    /cluster/status   -> JSON with every node's status, hints and stats
 * </pre>
 *
 * The router itself stores nothing, it hands every request to the {@link Coordinator}.
 */
public class RouterServer {

    private static final Logger LOG = Logger.getLogger(RouterServer.class.getName());
    private static final String KV_PREFIX = "/kv/";

    private final Coordinator coordinator;
    private final NodeClient client;
    private final HttpServer server;
    private final ExecutorService workers;

    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong deletes = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public RouterServer(int port, Coordinator coordinator, NodeClient client, int threads) throws IOException {
        this.coordinator = coordinator;
        this.client = client;
        this.server = HttpUtils.createServer(port);
        this.workers = Executors.newFixedThreadPool(threads);
        server.setExecutor(workers);
        server.createContext(KV_PREFIX, HttpUtils.withErrorHandling(this::handleKv));
        server.createContext("/health", HttpUtils.withErrorHandling(e -> sendText(e, 200, "OK\n")));
        server.createContext("/cluster/status", HttpUtils.withErrorHandling(this::handleStatus));
    }

    public void start() {
        server.start();
        LOG.info("Router listening on port " + getPort() + ", nodes: " + coordinator.nodes()
                + ", replication factor: " + coordinator.replicationFactor()
                + ", write quorum: " + coordinator.writeQuorum());
    }

    public void stop() {
        server.stop(0);
        workers.shutdown();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void handleKv(HttpExchange exchange) throws IOException {
        String key = HttpUtils.pathSuffix(exchange, KV_PREFIX);
        if (!HttpUtils.isValidKey(key)) {
            sendText(exchange, 400, "Invalid key. Use 1-256 characters from [A-Za-z0-9._:-]\n");
            return;
        }

        try {
            switch (exchange.getRequestMethod()) {
                case "GET" -> {
                    reads.incrementAndGet();
                    Optional<String> value = coordinator.get(key);
                    if (value.isPresent()) {
                        sendText(exchange, 200, value.get());
                    } else {
                        sendText(exchange, 404, "Key not found\n");
                    }
                }
                case "PUT" -> {
                    writes.incrementAndGet();
                    int acks = coordinator.put(key, HttpUtils.readBody(exchange));
                    exchange.getResponseHeaders().set("X-Replicas-Acked", String.valueOf(acks));
                    sendText(exchange, 200, "OK\n");
                }
                case "DELETE" -> {
                    deletes.incrementAndGet();
                    if (coordinator.delete(key)) {
                        sendText(exchange, 200, "Deleted\n");
                    } else {
                        sendText(exchange, 404, "Key not found\n");
                    }
                }
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, PUT, DELETE");
                    sendText(exchange, 405, "Method not allowed\n");
                }
            }
        } catch (ClusterUnavailableException e) {
            failed.incrementAndGet();
            LOG.warning("Request for key " + key + " failed: " + e.getMessage());
            sendText(exchange, 503, e.getMessage() + "\n");
        }
    }

    /**
     * Builds a JSON document such as:
     * <pre>
     * {"replicationFactor":2,"writeQuorum":1,
     *  "router":{"reads":10,"writes":4,"deletes":0,"failed":0},
     *  "nodes":[{"address":"node1:8080","status":"UP","pendingHints":0,"stats":{"keys":3,...}}, ...]}
     * </pre>
     * "stats" comes from each node's /stats endpoint and is null for nodes that are DOWN.
     */
    private void handleStatus(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\"replicationFactor\":").append(coordinator.replicationFactor())
                .append(",\"writeQuorum\":").append(coordinator.writeQuorum())
                .append(",\"router\":{\"reads\":").append(reads.get())
                .append(",\"writes\":").append(writes.get())
                .append(",\"deletes\":").append(deletes.get())
                .append(",\"failed\":").append(failed.get())
                .append("},\"nodes\":[");

        boolean first = true;
        for (ClusterNode node : coordinator.members()) {
            ClusterNode.Status status = node.status();
            // Skip DOWN nodes: asking them would only wait for a timeout.
            String stats = status == ClusterNode.Status.DOWN
                    ? null
                    : client.stats(node.address()).orElse(null);

            json.append(first ? "" : ",")
                    .append("{\"address\":").append(Json.quote(node.address()))
                    .append(",\"status\":").append(Json.quote(status.name()))
                    .append(",\"pendingHints\":").append(node.pendingHints())
                    .append(",\"stats\":").append(stats == null ? "null" : stats)
                    .append('}');
            first = false;
        }
        json.append("]}");
        HttpUtils.sendJson(exchange, 200, json.toString());
    }
}
