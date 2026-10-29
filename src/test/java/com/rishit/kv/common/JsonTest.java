package com.rishit.kv.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void plainTextIsQuoted() {
        assertEquals("\"node1:8080\"", Json.quote("node1:8080"));
    }

    @Test
    void specialCharactersAreEscaped() {
        assertEquals("\"say \\\"hi\\\"\"", Json.quote("say \"hi\""));
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"));
        assertEquals("\"line1\\nline2\\t\\u0001\"", Json.quote("line1\nline2\t\u0001"));
    }
}
