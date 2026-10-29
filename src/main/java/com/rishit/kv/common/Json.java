package com.rishit.kv.common;

/**
 * The status endpoints return small, fixed-shape JSON objects, so they are
 * built with a StringBuilder instead of pulling in a JSON library. The only
 * tricky part is escaping strings, which this class does.
 */
public final class Json {

    private Json() {
    }

    /** Returns the text as a JSON string literal, e.g. {@code say "hi"} -> {@code "say \"hi\""}. */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c)); // other control characters
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
