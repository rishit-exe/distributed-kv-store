package com.rishit.kv.store;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * Append-only log of every write, used to rebuild the in-memory map after a restart.
 *
 * <p>File format, one record per line:
 * <pre>
 * PUT &lt;base64 key&gt; &lt;base64 value&gt; &lt;crc32&gt;
 * DEL &lt;base64 key&gt; &lt;crc32&gt;
 * </pre>
 *
 * <ul>
 *   <li>Base64 is used so values containing spaces or newlines cannot break the line format.</li>
 *   <li>The CRC32 checksum detects a half-written record (for example if the process
 *       was killed in the middle of a write). Such records are skipped on replay.</li>
 * </ul>
 *
 * Methods are synchronized so only one thread writes to the file at a time.
 */
public class WriteAheadLog implements Closeable {

    private static final Logger LOG = Logger.getLogger(WriteAheadLog.class.getName());

    private final Path path;
    private final boolean syncOnWrite;

    private FileOutputStream fileOut;
    private Writer writer;
    private long recordCount;

    /**
     * @param syncOnWrite if true, every record is forced to the physical disk (fsync).
     *                    Safer against power loss but much slower. If false, records
     *                    are handed to the OS, which survives a process crash.
     */
    public WriteAheadLog(Path path, boolean syncOnWrite) throws IOException {
        this.path = path;
        this.syncOnWrite = syncOnWrite;
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        openForAppend();
    }

    /**
     * Reads every valid record and applies it to the map, in order.
     *
     * @return number of valid records in the log
     */
    public synchronized long replay(Map<String, String> data) throws IOException {
        long valid = 0;
        long lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                if (applyRecord(line, data)) {
                    valid++;
                } else {
                    LOG.warning("Skipping corrupted record at " + path + ":" + lineNumber);
                }
            }
        }
        recordCount = valid;
        return valid;
    }

    public synchronized void appendPut(String key, String value) throws IOException {
        append("PUT " + encode(key) + " " + encode(value));
    }

    public synchronized void appendDelete(String key) throws IOException {
        append("DEL " + encode(key));
    }

    /** Number of records currently in the log file. */
    public synchronized long recordCount() {
        return recordCount;
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }

    private void append(String record) throws IOException {
        writer.write(withChecksum(record));
        writer.write('\n');
        writer.flush();
        if (syncOnWrite) {
            fileOut.getFD().sync();
        }
        recordCount++;
    }

    private void openForAppend() throws IOException {
        // If the last write was cut off, the file does not end with '\n'.
        // Start a fresh line so the next record is not glued to the broken one.
        boolean needsNewline = !endsWithNewline(path);
        fileOut = new FileOutputStream(path.toFile(), true);
        writer = new BufferedWriter(new OutputStreamWriter(fileOut, UTF_8));
        if (needsNewline) {
            writer.write('\n');
            writer.flush();
        }
    }

    /** Returns false if the record is corrupted. */
    private static boolean applyRecord(String line, Map<String, String> data) {
        String record = verifyChecksum(line);
        if (record == null) {
            return false;
        }
        String[] parts = record.split(" ", -1);
        try {
            if (parts[0].equals("PUT") && parts.length == 3) {
                data.put(decode(parts[1]), decode(parts[2]));
                return true;
            }
            if (parts[0].equals("DEL") && parts.length == 2) {
                data.remove(decode(parts[1]));
                return true;
            }
        } catch (IllegalArgumentException e) {
            // invalid base64, treat as corrupted
        }
        return false;
    }

    private static String withChecksum(String record) {
        return record + " " + Long.toHexString(crc32(record));
    }

    /** Returns the record without its checksum, or null if the checksum does not match. */
    private static String verifyChecksum(String line) {
        int lastSpace = line.lastIndexOf(' ');
        if (lastSpace < 0) {
            return null;
        }
        String record = line.substring(0, lastSpace);
        try {
            long expected = Long.parseLong(line.substring(lastSpace + 1), 16);
            return expected == crc32(record) ? record : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(UTF_8));
        return crc.getValue();
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), UTF_8);
    }

    private static boolean endsWithNewline(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return true;
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(file.length() - 1);
            return file.read() == '\n';
        }
    }
}
