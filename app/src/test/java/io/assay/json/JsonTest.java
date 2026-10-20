package io.assay.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The codec underpins every stored artifact, so it gets its own tests covering the behaviours the rest of
 * the framework depends on.
 */
class JsonTest {

    @Test
    void readsObjectsArraysAndScalars() {
        Object value =
                Json.parse(
                        "{\"a\": 1, \"b\": [true, false, null], \"c\": {\"d\": \"text\"}, \"e\": 1.5, \"f\": -2e3}");

        Map<String, Object> object = assertInstanceOf(Map.class, value);
        assertEquals(1L, object.get("a"));
        assertEquals(List.of(true, false), List.of(((List<?>) object.get("b")).get(0), ((List<?>) object.get("b")).get(1)));
        assertNull(((List<?>) object.get("b")).get(2));
        assertEquals("text", ((Map<?, ?>) object.get("c")).get("d"));
        assertEquals(1.5, object.get("e"));
        assertEquals(-2000.0, object.get("f"));
    }

    /** Integral and fractional numbers stay distinct, in memory and on disk. */
    @Test
    void keepsIntegralAndFractionalNumbersDistinct() {
        Map<String, Object> object = Json.parseObject("{\"i\": 2, \"f\": 2.0}");

        assertInstanceOf(Long.class, object.get("i"));
        assertInstanceOf(Double.class, object.get("f"));
        assertEquals("{\"i\": 2, \"f\": 2.0}", Json.write(object));
    }

    @Test
    void writesRawUtf8AndEscapesOnlyWhatJsonRequires() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("text", "支持 \"quoted\"\tline\n上");
        object.put("path", "a/b");

        assertEquals("{\"text\": \"支持 \\\"quoted\\\"\\tline\\n上\", \"path\": \"a/b\"}", Json.write(object));
    }

    @Test
    void parsesEscapesIncludingSurrogatePairs() {
        assertEquals("\uD83D\uDE00 and \"q\" and \n", Json.parse("\"\\ud83d\\ude00 and \\\"q\\\" and \\n\""));
    }

    @Test
    void canonicalFormSortsKeysAndDropsSeparatorWhitespace() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 1);
        first.put("a", List.of(1, 2));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", List.of(1, 2));
        second.put("b", 1);

        assertEquals("{\"a\":[1,2],\"b\":1}", Json.canonical(first));
        assertEquals(Json.canonical(first), Json.canonical(second));
    }

    @Test
    void indentedOutputIsRereadableAndKeepsEmptyContainersCompact() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("list", List.of(Map.of("n", 1)));
        object.put("empty", Map.of());
        object.put("none", List.of());

        String text = Json.write(object, 2);
        assertEquals(
                "{\n  \"list\": [\n    {\n      \"n\": 1\n    }\n  ],\n  \"empty\": {},\n  \"none\": []\n}",
                text);
        assertEquals(Json.canonical(object), Json.canonical(Json.parse(text)));
    }

    @Test
    void extractsAnObjectFromModelProseAndCodeFences() {
        String prose = "Sure, here it is:\n```json\n{\"mode\": \"smoke\", \"nested\": {\"a\": 1}}\n```\nDone.";
        Map<String, Object> object = Json.parseObjectLoose(prose);

        assertEquals("smoke", object.get("mode"));
        assertEquals(1L, ((Map<?, ?>) object.get("nested")).get("a"));
    }

    @Test
    void looseExtractionSkipsBracesThatDoNotStartAnObject() {
        Map<String, Object> object = Json.parseObjectLoose("not json {oops} then {\"ok\": true} trailing");

        assertEquals(true, object.get("ok"));
    }

    @Test
    void rejectsTextWithoutAnObject() {
        assertThrows(JsonException.class, () -> Json.parseObjectLoose("no object here"));
        assertThrows(JsonException.class, () -> Json.parseObject("[1, 2]"));
    }

    @Test
    void rejectsMalformedDocuments() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": 1} extra"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
    }

    /** Models are walked into plain containers; scalars pass through and nulls survive. */
    @Test
    void normalizesModelsAndPreservesInsertionOrder() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("z", 1);
        source.put("a", null);
        source.put("nested", new Jsonable() {
            @Override
            public Object toJson() {
                return Map.of("inner", List.of(1, 2));
            }
        });

        assertEquals("{\"z\": 1, \"a\": null, \"nested\": {\"inner\": [1, 2]}}", Json.write(source));
    }

    @Test
    void numbersBeyondLongRangeStillRoundTripAsNumbers() {
        assertTrue(Json.write(Json.parse("1e400")).contains("Infinity"));
    }
}
