package com.rishit.kv.common;

/**
 * Reads configuration from environment variables, falling back to defaults.
 * Environment variables work the same way locally and inside Docker.
 */
public final class Env {

    private Env() {
    }

    public static String get(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    public static boolean getBoolean(String name, boolean defaultValue) {
        String value = get(name, null);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    public static int getInt(String name, int defaultValue) {
        String value = get(name, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number, got: " + value);
        }
    }
}
