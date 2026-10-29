package com.rishit.kv.router;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Talks to storage nodes over their HTTP API.
 *
 * Every call has a timeout, so a node that hangs is treated the same as a node
 * that is down instead of blocking the router forever. Any network error or
 * unexpected status code is reported as an IOException.
 */
public class NodeClient {

    private final HttpClient http;
    private final Duration timeout;

    public NodeClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    /** Returns the value, or empty if the node does not have the key. */
    public Optional<String> get(String node, String key) throws IOException {
        HttpResponse<String> response = send(request(node, "/kv/" + key).GET());
        return switch (response.statusCode()) {
            case 200 -> Optional.of(response.body());
            case 404 -> Optional.empty();
            default -> throw unexpected(node, response);
        };
    }

    public void put(String node, String key, String value) throws IOException {
        HttpResponse<String> response = send(request(node, "/kv/" + key)
                .PUT(HttpRequest.BodyPublishers.ofString(value)));
        if (response.statusCode() != 200) {
            throw unexpected(node, response);
        }
    }

    /** Returns true if the key existed on the node. */
    public boolean delete(String node, String key) throws IOException {
        HttpResponse<String> response = send(request(node, "/kv/" + key).DELETE());
        return switch (response.statusCode()) {
            case 200 -> true;
            case 404 -> false;
            default -> throw unexpected(node, response);
        };
    }

    /** Returns the node's /stats JSON, or empty if the node did not answer. */
    public Optional<String> stats(String node) {
        try {
            HttpResponse<String> response = send(request(node, "/stats").GET());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** True if the node answered its health endpoint in time. */
    public boolean isHealthy(String node) {
        try {
            return send(request(node, "/health").GET()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private HttpRequest.Builder request(String node, String path) {
        return HttpRequest.newBuilder(URI.create("http://" + node + path)).timeout(timeout);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for node", e);
        }
    }

    private static IOException unexpected(String node, HttpResponse<String> response) {
        return new IOException("Node " + node + " returned status " + response.statusCode());
    }
}
