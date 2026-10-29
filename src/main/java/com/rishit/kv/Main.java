package com.rishit.kv;

import com.rishit.kv.bench.Benchmark;
import com.rishit.kv.common.Env;
import com.rishit.kv.node.NodeServer;
import com.rishit.kv.router.ConsistentHashRing;
import com.rishit.kv.router.Coordinator;
import com.rishit.kv.router.HealthChecker;
import com.rishit.kv.router.NodeClient;
import com.rishit.kv.router.RouterServer;
import com.rishit.kv.store.PersistentKeyValueStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point. The same jar runs every role:
 *
 * <pre>
 * java -jar distributed-kv-store.jar node     # storage node
 * java -jar distributed-kv-store.jar router   # routes client requests to nodes
 * java -jar distributed-kv-store.jar bench    # load generator, see Benchmark
 * </pre>
 */
public final class Main {

    private static final String USAGE = "Usage: java -jar distributed-kv-store.jar <node|router|bench>";

    static {
        // One-line log format: "2026-10-30 12:00:00 INFO Node listening on port 8080"
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s%6$s%n");
    }

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "node";
        switch (mode) {
            case "node" -> startNode();
            case "router" -> startRouter();
            case "bench" -> Benchmark.run(Arrays.copyOfRange(args, 1, args.length));
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.err.println(USAGE);
                System.exit(1);
            }
        }
    }

    private static void startNode() throws IOException {
        int port = Env.getInt("PORT", 8081);
        int threads = Env.getInt("THREADS", 16);
        Path dataDir = Path.of(Env.get("DATA_DIR", "data/node-" + port));
        boolean fsync = Env.getBoolean("FSYNC", false);

        PersistentKeyValueStore store = new PersistentKeyValueStore(dataDir.resolve("kv.log"), fsync);
        NodeServer server = new NodeServer(port, store, threads);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try {
                store.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }));
        server.start();
    }

    private static void startRouter() throws IOException {
        int port = Env.getInt("PORT", 8080);
        int threads = Env.getInt("THREADS", 32);
        List<String> nodes = parseNodes(Env.get("NODES", "localhost:8081,localhost:8082,localhost:8083"));
        Duration timeout = Duration.ofMillis(Env.getInt("REQUEST_TIMEOUT_MS", 1000));

        int replicationFactor = Env.getInt("REPLICATION_FACTOR", 2);
        int writeQuorum = Env.getInt("WRITE_QUORUM", 1);

        ConsistentHashRing ring = new ConsistentHashRing(Env.getInt("VIRTUAL_NODES", 100));
        nodes.forEach(ring::addNode);

        NodeClient client = new NodeClient(timeout);
        Coordinator coordinator = new Coordinator(ring, client, replicationFactor, writeQuorum);

        HealthChecker healthChecker = new HealthChecker(coordinator, client, Env.getInt("FAILURE_THRESHOLD", 3));
        healthChecker.start(Duration.ofMillis(Env.getInt("HEALTH_CHECK_INTERVAL_MS", 1000)));

        RouterServer server = new RouterServer(port, coordinator, client, threads);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            healthChecker.stop();
            server.stop();
        }));
        server.start();
    }

    /** "host1:8081, host2:8082" -> ["host1:8081", "host2:8082"] */
    private static List<String> parseNodes(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
