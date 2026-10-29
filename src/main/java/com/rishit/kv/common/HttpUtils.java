package com.rishit.kv.common;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Small helpers shared by the HTTP servers (reading bodies, sending responses,
 * validating keys).
 */
public final class HttpUtils {

    private static final Logger LOG = Logger.getLogger(HttpUtils.class.getName());

    /** Values larger than 1 MB are rejected so one request cannot eat all the memory. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    /**
     * Keys are limited to URL-safe characters. This keeps the key usable as a
     * path segment without any encoding/decoding problems.
     */
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,256}");

    private HttpUtils() {
    }

    public static boolean isValidKey(String key) {
        return key != null && VALID_KEY.matcher(key).matches();
    }

    /** Returns the part of the request path after the given prefix, e.g. "/kv/user1" -> "user1". */
    public static String pathSuffix(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        return path.length() > prefix.length() ? path.substring(prefix.length()) : "";
    }

    /** Reads the request body as UTF-8 text, refusing bodies above {@link #MAX_BODY_BYTES}. */
    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
            return new String(bytes, UTF_8);
        }
    }

    public static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, body, "text/plain; charset=utf-8");
    }

    private static void send(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // -1 tells HttpServer that there is no response body.
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /**
     * Wraps a handler so that an unexpected exception turns into a 500 response
     * instead of a dropped connection, and the exchange is always closed.
     */
    public static HttpHandler withErrorHandling(HttpHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (BodyTooLargeException e) {
                trySend(exchange, 413, "Request body too large\n");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Request " + exchange.getRequestURI() + " failed", e);
                trySend(exchange, 500, "Internal server error\n");
            } finally {
                exchange.close();
            }
        };
    }

    private static void trySend(HttpExchange exchange, int status, String body) {
        try {
            sendText(exchange, status, body);
        } catch (IOException ignored) {
            // Headers were probably already sent; nothing more we can do.
        }
    }

    /** Thrown when a request body is bigger than {@link #MAX_BODY_BYTES}. */
    public static class BodyTooLargeException extends IOException {
        public BodyTooLargeException() {
            super("Request body larger than " + MAX_BODY_BYTES + " bytes");
        }
    }
}
