package com.rishit.kv.store;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Store that keeps all data in memory for fast reads, and records every write
 * in a {@link WriteAheadLog} so the data survives a restart.
 *
 * <p>Write path: append to the log first, then update the map. If the process
 * dies after the append, the write is recovered on the next start. If it dies
 * before, the client never got an "OK", so nothing acknowledged is lost.
 *
 * <p>Concurrency: reads go straight to the ConcurrentHashMap and never block.
 * Writes are synchronized so the order of records in the log always matches
 * the order in which the map was updated. Without the lock, two writes to the
 * same key could reach the log in one order and the map in the other, and the
 * value after a restart would differ from the value before it.
 */
public class PersistentKeyValueStore implements KeyValueStore, Closeable {

    private static final Logger LOG = Logger.getLogger(PersistentKeyValueStore.class.getName());

    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final WriteAheadLog log;

    public PersistentKeyValueStore(Path logFile, boolean syncOnWrite) throws IOException {
        this.log = new WriteAheadLog(logFile, syncOnWrite);
        long records = log.replay(data);
        LOG.info("Recovered " + data.size() + " keys from " + records + " log records in " + logFile);
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public synchronized void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        try {
            log.appendPut(key, value);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to log", e);
        }
        data.put(key, value);
    }

    @Override
    public synchronized boolean delete(String key) {
        Objects.requireNonNull(key, "key");
        if (!data.containsKey(key)) {
            return false; // nothing to delete, no need to log it
        }
        try {
            log.appendDelete(key);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to log", e);
        }
        data.remove(key);
        return true;
    }

    @Override
    public int size() {
        return data.size();
    }

    @Override
    public synchronized void close() throws IOException {
        log.close();
    }
}
