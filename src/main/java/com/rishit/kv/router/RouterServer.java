package com.rishit.kv.router;

import static com.rishit.kv.common.HttpUtils.sendText;

import com.rishit.kv.common.HttpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * </pre>
 *
 * The router itself stores nothing, it hands every request to the {@link Coordinator}.
 */
public class RouterServer {

    private static final Logger LOG = Logger.getLogger(RouterServer.class.getName());
    private static final String KV_PREFIX = "/kv/";

    private final Coordinator coordinator;
    private final HttpServer server;
    private final ExecutorService workers;

    public RouterServer(int port, Coordinator coordinator, int threads) throws IOException {
        this.coordinator = coordinator;
        this.server = HttpUtils.createServer(port);
        this.workers = Executors.newFixedThreadPool(threads);
        server.setExecutor(workers);
        server.createContext(KV_PREFIX, HttpUtils.withErrorHandling(this::handleKv));
        server.createContext("/health", HttpUtils.withErrorHandling(e -> sendText(e, 200, "OK\n")));
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
                    Optional<String> value = coordinator.get(key);
                    if (value.isPresent()) {
                        sendText(exchange, 200, value.get());
                    } else {
                        sendText(exchange, 404, "Key not found\n");
                    }
                }
                case "PUT" -> {
                    int acks = coordinator.put(key, HttpUtils.readBody(exchange));
                    exchange.getResponseHeaders().set("X-Replicas-Acked", String.valueOf(acks));
                    sendText(exchange, 200, "OK\n");
                }
                case "DELETE" -> {
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
            LOG.warning("Request for key " + key + " failed: " + e.getMessage());
            sendText(exchange, 503, e.getMessage() + "\n");
        }
    }
}
