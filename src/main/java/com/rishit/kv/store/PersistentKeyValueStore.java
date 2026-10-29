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
 *
 * <p>Compaction: the log keeps every old value, so it grows forever. When it has
 * more than twice as many records as live keys (and at least
 * {@code compactionThreshold} records), it is rewritten with only the live data.
 */
public class PersistentKeyValueStore implements KeyValueStore, Closeable {

    private static final Logger LOG = Logger.getLogger(PersistentKeyValueStore.class.getName());
    public static final long DEFAULT_COMPACTION_THRESHOLD = 10_000;

    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final WriteAheadLog log;
    private final long compactionThreshold;

    public PersistentKeyValueStore(Path logFile, boolean syncOnWrite) throws IOException {
        this(logFile, syncOnWrite, DEFAULT_COMPACTION_THRESHOLD);
    }

    public PersistentKeyValueStore(Path logFile, boolean syncOnWrite, long compactionThreshold)
            throws IOException {
        this.log = new WriteAheadLog(logFile, syncOnWrite);
        this.compactionThreshold = compactionThreshold;
        long records = log.replay(data);
        LOG.info("Recovered " + data.size() + " keys from " + records + " log records in " + logFile);
        compactIfNeeded();
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
            data.put(key, value);
            compactIfNeeded();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to log", e);
        }
    }

    @Override
    public synchronized boolean delete(String key) {
        Objects.requireNonNull(key, "key");
        if (!data.containsKey(key)) {
            return false; // nothing to delete, no need to log it
        }
        try {
            log.appendDelete(key);
            data.remove(key);
            compactIfNeeded();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to log", e);
        }
        return true;
    }

    @Override
    public int size() {
        return data.size();
    }

    /** Number of records in the log file (exposed for tests and monitoring). */
    public long logRecordCount() {
        return log.recordCount();
    }

    @Override
    public synchronized void close() throws IOException {
        log.close();
    }

    /** Called while holding the lock, so no write can happen during compaction. */
    private void compactIfNeeded() throws IOException {
        long records = log.recordCount();
        if (records >= compactionThreshold && records > 2L * data.size()) {
            log.compact(data);
            LOG.info("Compacted log from " + records + " to " + log.recordCount() + " records");
        }
    }
}
