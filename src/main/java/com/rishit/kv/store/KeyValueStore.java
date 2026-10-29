package com.rishit.kv.store;

import java.util.Optional;

/**
 * Basic contract for a key-value store.
 *
 * Keys and values are plain strings. Implementations decide where the data
 * lives (memory, disk, ...), callers only depend on this interface.
 */
public interface KeyValueStore {

    /** Returns the value for the key, or an empty Optional if it does not exist. */
    Optional<String> get(String key);

    /** Inserts the key or overwrites its current value. */
    void put(String key, String value);

    /** Removes the key. Returns true if the key existed. */
    boolean delete(String key);

    /** Number of keys currently stored. */
    int size();
}
