package com.rishit.kv.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryKeyValueStoreTest {

    private KeyValueStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryKeyValueStore();
    }

    @Test
    void getReturnsEmptyForMissingKey() {
        assertEquals(Optional.empty(), store.get("missing"));
    }

    @Test
    void putThenGetReturnsValue() {
        store.put("name", "rishit");
        assertEquals(Optional.of("rishit"), store.get("name"));
    }

    @Test
    void putOverwritesExistingValue() {
        store.put("name", "old");
        store.put("name", "new");

        assertEquals(Optional.of("new"), store.get("name"));
        assertEquals(1, store.size());
    }

    @Test
    void deleteRemovesKey() {
        store.put("name", "rishit");

        assertTrue(store.delete("name"));
        assertEquals(Optional.empty(), store.get("name"));
        assertEquals(0, store.size());
    }

    @Test
    void deleteReturnsFalseForMissingKey() {
        assertFalse(store.delete("missing"));
    }

    @Test
    void nullKeyOrValueIsRejected() {
        assertThrows(NullPointerException.class, () -> store.put(null, "v"));
        assertThrows(NullPointerException.class, () -> store.put("k", null));
        assertThrows(NullPointerException.class, () -> store.get(null));
    }
}
