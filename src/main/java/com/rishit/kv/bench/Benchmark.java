package com.rishit.kv.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple load generator for the HTTP API (works against a node or the router).
 *
 * <pre>
 * java -jar distributed-kv-store.jar bench --url=http://localhost:8080 --threads=8 \
 *      --requests=40000 --warmup=10000 --read-ratio=0.8 --keys=1000 --value-size=100
 * </pre>
 *
 * 1. Writes every key once, so reads find data.
 * 2. Warm-up: runs {@code warmup} requests and throws the results away. The
 *    JVM compiles hot code (JIT) during the first seconds, so early requests
 *    are much slower than later ones and would distort the numbers.
 * 3. Measured run: {@code threads} workers send {@code requests} requests in
 *    total. Each one is a read with probability {@code read-ratio}, otherwise
 *    a write, to a random key.
 * 4. Prints throughput and latency percentiles for reads and writes.
 */
public final class Benchmark {

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String url;
    private final int threads;
    private final double readRatio;
    private final int keys;
    private final String value;
    private final ExecutorService pool;

    private Benchmark(String url, int threads, double readRatio, int keys, int valueSize) {
        this.url = url;
        this.threads = threads;
        this.readRatio = readRatio;
        this.keys = keys;
        this.value = "x".repeat(valueSize);
        this.pool = Executors.newFixedThreadPool(threads);
    }

    public static void run(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        String url = options.getOrDefault("url", "http://localhost:8080");
        int threads = Integer.parseInt(options.getOrDefault("threads", "8"));
        int requests = Integer.parseInt(options.getOrDefault("requests", "40000"));
        int warmup = Integer.parseInt(options.getOrDefault("warmup", "10000"));
        double readRatio = Double.parseDouble(options.getOrDefault("read-ratio", "0.8"));
        int keys = Integer.parseInt(options.getOrDefault("keys", "1000"));
        int valueSize = Integer.parseInt(options.getOrDefault("value-size", "100"));

        System.out.printf(Locale.ROOT, "Benchmark against %s%n", url);
        System.out.printf(Locale.ROOT,
                "threads=%d requests=%d warmup=%d read-ratio=%.2f keys=%d value-size=%dB%n%n",
                threads, requests, warmup, readRatio, keys, valueSize);

        Benchmark benchmark = new Benchmark(url, threads, readRatio, keys, valueSize);
        try {
            benchmark.loadKeys();
            if (warmup > 0) {
                benchmark.mixedWorkload(warmup);
            }

            long start = System.nanoTime();
            Stats[] result = benchmark.mixedWorkload(requests);
            double seconds = (System.nanoTime() - start) / 1e9;
            printReport(result[0], result[1], seconds);
        } finally {
            benchmark.pool.shutdown();
        }
    }

    /** Writes every key once, split across the worker threads. */
    private void loadKeys() throws Exception {
        List<Future<Stats>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int first = t;
            futures.add(pool.submit(() -> {
                Stats writes = new Stats();
                for (int k = first; k < keys; k += threads) {
                    timedRequest(put("bench-" + k), writes);
                }
                return writes;
            }));
        }
        for (Future<Stats> future : futures) {
            future.get();
        }
    }

    /** Runs the read/write mix and returns {reads, writes}. */
    private Stats[] mixedWorkload(int requests) throws Exception {
        int perThread = requests / threads;
        List<Future<Stats[]>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                Stats reads = new Stats();
                Stats writes = new Stats();
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < perThread; i++) {
                    String key = "bench-" + random.nextInt(keys);
                    if (random.nextDouble() < readRatio) {
                        timedRequest(get(key), reads);
                    } else {
                        timedRequest(put(key), writes);
                    }
                }
                return new Stats[] {reads, writes};
            }));
        }

        Stats allReads = new Stats();
        Stats allWrites = new Stats();
        for (Future<Stats[]> future : futures) {
            Stats[] result = future.get();
            allReads.addAll(result[0]);
            allWrites.addAll(result[1]);
        }
        return new Stats[] {allReads, allWrites};
    }

    private HttpRequest get(String key) {
        return HttpRequest.newBuilder(URI.create(url + "/kv/" + key)).GET().build();
    }

    private HttpRequest put(String key) {
        return HttpRequest.newBuilder(URI.create(url + "/kv/" + key))
                .PUT(HttpRequest.BodyPublishers.ofString(value))
                .build();
    }

    private void timedRequest(HttpRequest request, Stats stats) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                stats.record(System.nanoTime() - start);
            } else {
                stats.errors++;
            }
        } catch (Exception e) {
            stats.errors++;
        }
    }

    private static void printReport(Stats reads, Stats writes, double seconds) {
        long ops = reads.count + writes.count;
        System.out.printf(Locale.ROOT, "Total time:  %.2f s%n", seconds);
        System.out.printf(Locale.ROOT, "Throughput:  %.0f ops/s%n", ops / seconds);
        System.out.printf(Locale.ROOT, "Errors:      %d%n%n", reads.errors + writes.errors);
        System.out.printf(Locale.ROOT, "%-8s %8s %8s %8s %8s %8s  (latency in ms)%n",
                "", "count", "p50", "p95", "p99", "max");
        reads.print("reads");
        writes.print("writes");
    }

    /** "--threads=8 --url=x" -> {threads=8, url=x} */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Expected --name=value, got: " + arg);
            }
            int eq = arg.indexOf('=');
            options.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return options;
    }

    /** Latencies of one kind of request. Each worker has its own, so no locking is needed. */
    private static final class Stats {
        private long[] latencies = new long[1024];
        private int count;
        private int errors;

        void record(long nanos) {
            if (count == latencies.length) {
                latencies = Arrays.copyOf(latencies, count * 2);
            }
            latencies[count++] = nanos;
        }

        void addAll(Stats other) {
            for (int i = 0; i < other.count; i++) {
                record(other.latencies[i]);
            }
            errors += other.errors;
        }

        void print(String label) {
            if (count == 0) {
                System.out.printf(Locale.ROOT, "%-8s %8d%n", label, 0);
                return;
            }
            long[] sorted = Arrays.copyOf(latencies, count);
            Arrays.sort(sorted);
            System.out.printf(Locale.ROOT, "%-8s %8d %8.2f %8.2f %8.2f %8.2f%n", label, count,
                    millis(percentile(sorted, 50)), millis(percentile(sorted, 95)),
                    millis(percentile(sorted, 99)), millis(sorted[sorted.length - 1]));
        }

        /** Nearest-rank percentile: the value below which p% of the samples fall. */
        private static long percentile(long[] sorted, double p) {
            int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, index)];
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }
}
