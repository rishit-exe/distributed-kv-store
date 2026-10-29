package com.rishit.kv.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteAheadLogTest {

    @TempDir
    Path tempDir;

    @Test
    void replayRebuildsPutsAndDeletesInOrder() throws Exception {
        Path file = tempDir.resolve("node.log");
        try (WriteAheadLog log = new WriteAheadLog(file, false)) {
            log.appendPut("a", "1");
            log.appendPut("b", "2");
            log.appendPut("a", "3");
            log.appendDelete("b");
        }

        Map<String, String> data = new HashMap<>();
        try (WriteAheadLog log = new WriteAheadLog(file, false)) {
            assertEquals(4, log.replay(data));
        }
        assertEquals(Map.of("a", "3"), data);
    }

    @Test
    void valuesWithSpacesNewlinesAndUnicodeSurvive() throws Exception {
        Path file = tempDir.resolve("node.log");
        String tricky = "hello world\nsecond line\twith tab, emoji \u2764 and empty next";
        try (WriteAheadLog log = new WriteAheadLog(file, true)) {
            log.appendPut("tricky", tricky);
            log.appendPut("empty", "");
        }

        Map<String, String> data = replay(file);
        assertEquals(tricky, data.get("tricky"));
        assertEquals("", data.get("empty"));
    }

    @Test
    void corruptedRecordIsSkipped() throws Exception {
        Path file = tempDir.resolve("node.log");
        try (WriteAheadLog log = new WriteAheadLog(file, false)) {
            log.appendPut("a", "1");
            log.appendPut("b", "2");
        }
        // Flip one character in the first record so its checksum no longer matches.
        List<String> lines = Files.readAllLines(file, UTF_8);
        lines.set(0, lines.get(0).replaceFirst("PUT ", "PUT X"));
        Files.write(file, lines, UTF_8);

        assertEquals(Map.of("b", "2"), replay(file));
    }

    @Test
    void halfWrittenLastRecordIsIgnoredAndNewWritesStillWork() throws Exception {
        Path file = tempDir.resolve("node.log");
        try (WriteAheadLog log = new WriteAheadLog(file, false)) {
            log.appendPut("a", "1");
        }
        // Simulate a crash in the middle of writing a record (no checksum, no newline).
        Files.writeString(file, "PUT Yg== dmFs", UTF_8, StandardOpenOption.APPEND);

        try (WriteAheadLog log = new WriteAheadLog(file, false)) {
            log.appendPut("c", "3");
        }

        assertEquals(Map.of("a", "1", "c", "3"), replay(file));
    }

    private Map<String, String> replay(Path file) throws Exception {
        Map<String, String> data = new HashMap<>();
        try (WriteAheadLog log = new WriteAheadLog(file, false)) {
            log.replay(data);
        }
        return data;
    }
}
