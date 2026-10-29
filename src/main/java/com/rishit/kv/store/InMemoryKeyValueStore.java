package com.rishit.kv.store;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Simplest possible store: everything lives in a HashMap.
 * Data is lost when the process stops.
 */
public class InMemoryKeyValueStore implements KeyValueStore {

    private final Map<String, String> data = new HashMap<>();

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
