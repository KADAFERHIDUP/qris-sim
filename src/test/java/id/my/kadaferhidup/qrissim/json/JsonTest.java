package id.my.kadaferhidup.qrissim.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    record Sample(String merchantId, long amount, Instant paidAt, boolean ok) {
    }

    @Test
    void writesRecordsInSnakeCase() {
        String json = Json.write(new Sample("M001", 25_000, Instant.parse("2026-11-21T00:00:00Z"), true));
        assertEquals("{\"merchant_id\":\"M001\",\"amount\":25000,\"paid_at\":\"2026-11-21T00:00:00Z\",\"ok\":true}", json);
    }

    @Test
    void escapesStringsSafelyForHtml() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003cscript>\"", Json.write("a\"b\\c\n<script>"));
    }

    @Test
    void parsesNestedValues() {
        Object v = Json.parse("{\"a\": [1, 2.5, \"x\", true, null], \"b\": {\"c\": \"\\u0041\"}}");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", java.util.Arrays.asList(1L, 2.5, "x", Boolean.TRUE, null));
        expected.put("b", Map.of("c", "A"));
        assertEquals(expected, v);
    }

    @Test
    void roundTripsAMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", 10_000L);
        m.put("tags", List.of("x", "y"));
        assertEquals(m, Json.parse(Json.write(m)));
    }

    @Test
    void rejectsBrokenInput() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\": 1} x"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1, 2"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1]"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[".repeat(100)));
    }

    @Test
    void emptyBodyIsAnEmptyObject() {
        assertEquals(Map.of(), Json.parseObject(""));
    }
}
