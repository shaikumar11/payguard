package com.payguard.api;

import java.util.*;

/** Tiny JSON helper: parses a flat object of strings and numbers, and writes simple objects. */
public final class MiniJson {
    private MiniJson() { }

    public static Map<String, String> parseFlat(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int[] i = {0};
        skip(s, i);
        expect(s, i, '{');
        skip(s, i);
        if (peek(s, i) == '}') return out;
        while (true) {
            skip(s, i);
            String key = readString(s, i);
            skip(s, i);
            expect(s, i, ':');
            skip(s, i);
            out.put(key, peek(s, i) == '"' ? readString(s, i) : readBare(s, i));
            skip(s, i);
            char c = next(s, i);
            if (c == '}') return out;
            if (c != ',') throw new IllegalArgumentException("invalid JSON: expected , or } at " + (i[0] - 1));
        }
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }

    public static String array(Collection<String> items) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        items.forEach(x -> j.add(quote(x)));
        return j.toString();
    }

    private static void skip(String s, int[] i) { while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++; }
    private static char peek(String s, int[] i) { if (i[0] >= s.length()) throw new IllegalArgumentException("invalid JSON: unexpected end"); return s.charAt(i[0]); }
    private static char next(String s, int[] i) { char c = peek(s, i); i[0]++; return c; }
    private static void expect(String s, int[] i, char c) {
        if (next(s, i) != c) throw new IllegalArgumentException("invalid JSON: expected '" + c + "' at " + (i[0] - 1));
    }

    private static String readString(String s, int[] i) {
        expect(s, i, '"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next(s, i);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = next(s, i);
                b.append(switch (e) { case 'n' -> '\n'; case 't' -> '\t'; default -> e; });
            } else b.append(c);
        }
    }

    private static String readBare(String s, int[] i) {
        int start = i[0];
        while (i[0] < s.length() && ",}".indexOf(s.charAt(i[0])) < 0 && !Character.isWhitespace(s.charAt(i[0]))) i[0]++;
        if (start == i[0]) throw new IllegalArgumentException("invalid JSON: empty value at " + start);
        return s.substring(start, i[0]);
    }
}
