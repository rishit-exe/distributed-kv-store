package com.rishit.kv.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentKeyValueStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void dataSurvivesRestart() throws Exception {
        Path logFile = tempDir.resolve("kv.log");
        try (PersistentKeyValueStore store = new PersistentKeyValueStore(logFile, false)) {
            store.put("user1", "rishit");
            store.put("user2", "temp");
            store.put("user1", "rishit-updated");
            store.delete("user2");
        }

        try (PersistentKeyValueStore restarted = new PersistentKeyValueStore(logFile, false)) {
            assertEquals(Optional.of("rishit-updated"), restarted.get("user1"));
            assertEquals(Optional.empty(), restarted.get("user2"));
            assertEquals(1, restarted.size());
        }
    }

    @Test
    void deletingMissingKeyReturnsFalse() throws Exception {
        try (PersistentKeyValueStore store = new PersistentKeyValueStore(tempDir.resolve("kv.log"), false)) {
            assertFalse(store.delete("missing"));
        }
    }

    @Test
    void concurrentWritesGiveSameStateAfterRestart() throws Exception {
        Path logFile = tempDir.resolve("kv.log");
        String[] before = new String[20];

        try (PersistentKeyValueStore store = new PersistentKeyValueStore(logFile, false)) {
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int threadId = t;
                Thread thread = new Thread(() -> {
                    for (int i = 0; i < 2_000; i++) {
                        store.put("key" + (i % 20), "t" + threadId + "-" + i);
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
            for (int i = 0; i < 20; i++) {
                before[i] = store.get("key" + i).orElseThrow();
            }
        }

        try (PersistentKeyValueStore restarted = new PersistentKeyValueStore(logFile, false)) {
            for (int i = 0; i < 20; i++) {
                assertEquals(before[i], restarted.get("key" + i).orElseThrow());
            }
        }
    }
}
