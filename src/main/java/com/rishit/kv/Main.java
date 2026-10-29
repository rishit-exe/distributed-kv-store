package com.rishit.kv;

import com.rishit.kv.common.Env;
import com.rishit.kv.node.NodeServer;
import com.rishit.kv.store.InMemoryKeyValueStore;
import java.io.IOException;

/**
 * Entry point. The same jar runs every role:
 *
 * <pre>
 * java -jar distributed-kv-store.jar node
 * </pre>
 */
public final class Main {

    static {
        // One-line log format: "2026-10-30 12:00:00 INFO Node listening on port 8080"
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s%6$s%n");
    }

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 ? args[0] : "node";
        switch (mode) {
            case "node" -> startNode();
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.err.println("Usage: java -jar distributed-kv-store.jar node");
                System.exit(1);
            }
        }
    }

    private static void startNode() throws IOException {
        int port = Env.getInt("PORT", 8081);

        NodeServer server = new NodeServer(port, new InMemoryKeyValueStore());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
    }
}
