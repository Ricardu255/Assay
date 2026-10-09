package io.assay.testing;

import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for the test suite.
 *
 * <p>Most assertions read values back out of the JSON-shaped result maps, where every number arrives as
 * a {@link Long} and every list as an untyped {@link List}. These accessors keep the tests about the
 * behaviour rather than about casting.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static EvalCase evalCase(String caseId, Object payload, Map<String, Object> expected, String suite) {
        return new EvalCase(caseId, payload, expected, "default", Map.of(), suite);
    }

    public static EvalCase routingCase(
            String caseId, String message, String route, Map<String, Object> metadata, String suite) {
        return new EvalCase(caseId, Map.of("message", message), Map.of("route", route), "routing", metadata, suite);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    public static List<Map<String, Object>> mapsOf(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : asList(value)) {
            result.add(asMap(item));
        }
        return result;
    }

    public static List<String> stringsOf(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : asList(value)) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    public static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Reads the stored run summary for a run from the JSON artifact it produced. */
    public static Map<String, Object> readJson(Path path) {
        return Json.parseObject(AtomicFiles.readText(path));
    }
}
