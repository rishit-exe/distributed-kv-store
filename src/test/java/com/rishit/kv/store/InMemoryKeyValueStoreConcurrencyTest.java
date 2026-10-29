package com.rishit.kv.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class InMemoryKeyValueStoreConcurrencyTest {

    private static final int THREADS = 8;
    private static final int KEYS_PER_THREAD = 10_000;

    @Test
    void concurrentPutsFromManyThreadsAreNotLost() throws Exception {
        KeyValueStore store = new InMemoryKeyValueStore();

        runConcurrently(threadId -> {
            for (int i = 0; i < KEYS_PER_THREAD; i++) {
                store.put("t" + threadId + "-k" + i, "v" + i);
            }
        });

        assertEquals(THREADS * KEYS_PER_THREAD, store.size());
        assertEquals(Optional.of("v42"), store.get("t3-k42"));
    }

    @Test
    void concurrentWritesToSameKeysKeepOneOfTheWrittenValues() throws Exception {
        KeyValueStore store = new InMemoryKeyValueStore();

        runConcurrently(threadId -> {
            for (int i = 0; i < KEYS_PER_THREAD; i++) {
                store.put("shared-" + (i % 100), "from-" + threadId);
            }
        });

        assertEquals(100, store.size());
        for (int i = 0; i < 100; i++) {
            String value = store.get("shared-" + i).orElseThrow();
            assertTrue(value.startsWith("from-"), "unexpected value " + value);
        }
    }

    /** Starts all threads at the same moment (using a latch) to maximise contention. */
    private void runConcurrently(ThreadTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                startSignal.await();
                task.run(threadId);
                return null;
            }));
        }

        startSignal.countDown();
        for (Future<?> future : futures) {
            future.get(); // rethrows any exception from the worker thread
        }
        pool.shutdown();
    }

    @FunctionalInterface
    private interface ThreadTask {
        void run(int threadId);
    }
}
