package com.rishit.kv.node;

import static com.rishit.kv.common.HttpUtils.sendText;

import com.rishit.kv.common.HttpUtils;
import com.rishit.kv.store.KeyValueStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * HTTP front end for a single storage node.
 *
 * <pre>
 * GET    /kv/{key}   -> 200 with the value, or 404
 * PUT    /kv/{key}   -> stores the request body as the value
 * DELETE /kv/{key}   -> 200 if deleted, 404 if the key did not exist
 * GET    /health     -> 200 "OK" while the node is running
 * </pre>
 */
public class NodeServer {

    private static final Logger LOG = Logger.getLogger(NodeServer.class.getName());
    private static final String KV_PREFIX = "/kv/";

    private final KeyValueStore store;
    private final HttpServer server;
    private final ExecutorService workers;

    /**
     * @param threads number of worker threads. Without an executor, HttpServer
     *                handles every request on its single dispatcher thread, so
     *                one slow request would block all the others.
     */
    public NodeServer(int port, KeyValueStore store, int threads) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.workers = Executors.newFixedThreadPool(threads);
        server.setExecutor(workers);
        server.createContext(KV_PREFIX, HttpUtils.withErrorHandling(this::handleKv));
        server.createContext("/health", HttpUtils.withErrorHandling(this::handleHealth));
    }

    public void start() {
        server.start();
        LOG.info("Node listening on port " + getPort());
    }

    public void stop() {
        server.stop(0);
        workers.shutdown();
    }

    /** Actual port (useful when the server was created with port 0 in tests). */
    public int getPort() {
        return server.getAddress().getPort();
    }

    private void handleKv(HttpExchange exchange) throws IOException {
        String key = HttpUtils.pathSuffix(exchange, KV_PREFIX);
        if (!HttpUtils.isValidKey(key)) {
            sendText(exchange, 400, "Invalid key. Use 1-256 characters from [A-Za-z0-9._:-]\n");
            return;
        }

        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                Optional<String> value = store.get(key);
                if (value.isPresent()) {
                    sendText(exchange, 200, value.get());
                } else {
                    sendText(exchange, 404, "Key not found\n");
                }
            }
            case "PUT" -> {
                String value = HttpUtils.readBody(exchange);
                store.put(key, value);
                sendText(exchange, 200, "OK\n");
            }
            case "DELETE" -> {
                if (store.delete(key)) {
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
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendText(exchange, 200, "OK\n");
    }
}
