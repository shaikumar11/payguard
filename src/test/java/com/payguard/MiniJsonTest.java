package com.payguard;

import com.payguard.api.MiniJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MiniJsonTest {
    @Test void parsesStringsAndNumbers() {
        var m = MiniJson.parseFlat(" { \"a\" : \"x y\", \"n\": 42 , \"q\":\"he said \\\"hi\\\"\" } ");
        assertEquals("x y", m.get("a"));
        assertEquals("42", m.get("n"));
        assertEquals("he said \"hi\"", m.get("q"));
    }

    @Test void emptyObject() { assertTrue(MiniJson.parseFlat("{}").isEmpty()); }

    @Test void rejectsBrokenInput() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseFlat("{\"a\":"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseFlat("[1]"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseFlat("{\"a\" 1}"));
    }

    @Test void quotesAndArrays() {
        assertEquals("\"a\\\"b\"", MiniJson.quote("a\"b"));
        assertEquals("[\"x\",\"y\"]", MiniJson.array(java.util.List.of("x", "y")));
    }
}
