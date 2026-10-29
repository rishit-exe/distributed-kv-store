package com.rishit.kv.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rishit.kv.store.InMemoryKeyValueStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeServerTest {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private NodeServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = new NodeServer(0, new InMemoryKeyValueStore(), 4); // port 0 = pick any free port
        server.start();
        baseUrl = "http://localhost:" + server.getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void putThenGetReturnsValue() throws Exception {
        assertEquals(200, send("PUT", "/kv/user1", "rishit").statusCode());

        HttpResponse<String> response = send("GET", "/kv/user1", null);
        assertEquals(200, response.statusCode());
        assertEquals("rishit", response.body());
    }

    @Test
    void getMissingKeyReturns404() throws Exception {
        assertEquals(404, send("GET", "/kv/missing", null).statusCode());
    }

    @Test
    void deleteRemovesKey() throws Exception {
        send("PUT", "/kv/user1", "rishit");

        assertEquals(200, send("DELETE", "/kv/user1", null).statusCode());
        assertEquals(404, send("GET", "/kv/user1", null).statusCode());
        assertEquals(404, send("DELETE", "/kv/user1", null).statusCode());
    }

    @Test
    void invalidKeyReturns400() throws Exception {
        assertEquals(400, send("GET", "/kv/", null).statusCode());
        assertEquals(400, send("GET", "/kv/bad%20key", null).statusCode());
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        assertEquals(405, send("POST", "/kv/user1", "x").statusCode());
    }

    @Test
    void tooLargeBodyReturns413() throws Exception {
        String big = "x".repeat(1024 * 1024 + 1);
        assertEquals(413, send("PUT", "/kv/big", big).statusCode());
    }

    @Test
    void healthReturnsOk() throws Exception {
        assertEquals(200, send("GET", "/health", null).statusCode());
    }

    @Test
    void handlesManyConcurrentRequests() throws Exception {
        int requests = 200;
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/kv/key" + i))
                    .PUT(HttpRequest.BodyPublishers.ofString("value" + i))
                    .build();
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        for (CompletableFuture<HttpResponse<String>> future : futures) {
            assertEquals(200, future.get().statusCode());
        }

        for (int i = 0; i < requests; i += 37) {
            assertEquals("value" + i, send("GET", "/kv/key" + i, null).body());
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, publisher)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
