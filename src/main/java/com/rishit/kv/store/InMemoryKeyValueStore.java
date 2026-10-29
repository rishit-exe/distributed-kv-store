package com.rishit.kv.store;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store that keeps everything in memory. Data is lost when the process stops.
 *
 * Uses a ConcurrentHashMap because the HTTP server calls it from many threads
 * at once. A plain HashMap can lose updates or get corrupted under concurrent
 * writes. ConcurrentHashMap makes each single operation (get/put/remove)
 * atomic without one global lock, so reads never block.
 */
public class InMemoryKeyValueStore implements KeyValueStore {

    private final Map<String, String> data = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        data.put(key, value);
    }

    @Override
    public boolean delete(String key) {
        Objects.requireNonNull(key, "key");
        return data.remove(key) != null;
    }

    @Override
    public int size() {
        return data.size();
    }
}
