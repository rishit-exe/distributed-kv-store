package com.rishit.kv.router;

/**
 * One write operation: a PUT (value != null) or a DELETE (value == null).
 * Writes that a node missed while it was down are kept as "hints" and
 * replayed when it comes back.
 */
public record Write(String key, String value) {

    public static Write put(String key, String value) {
        return new Write(key, value);
    }

    public static Write delete(String key) {
        return new Write(key, null);
    }

    public boolean isDelete() {
        return value == null;
    }
}
