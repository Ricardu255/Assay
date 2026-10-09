package io.assay.model;

import io.assay.json.Jsonable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.assay.support.Immutable;

/** Outcome of one rule or built-in check. */
public record CheckResult(
        String layer,
        String level,
        String name,
        boolean passed,
        Object actual,
        Object expected,
        String message,
        List<String> suspectedModules)
        implements Jsonable {

    public CheckResult {
        message = message == null ? "" : message;
        suspectedModules = suspectedModules == null ? List.of() : Immutable.list(suspectedModules);
    }

    public CheckResult(String layer, String level, String name, boolean passed) {
        this(layer, level, name, passed, null, null, "", List.of());
    }

    public CheckResult(
            String layer, String level, String name, boolean passed, Object actual, Object expected) {
        this(layer, level, name, passed, actual, expected, "", List.of());
    }

    public CheckResult(
            String layer,
            String level,
            String name,
            boolean passed,
            Object actual,
            Object expected,
            String message) {
        this(layer, level, name, passed, actual, expected, message, List.of());
    }

    public CheckResult suspectedModules(List<String> modules) {
        return new CheckResult(layer, level, name, passed, actual, expected, message, modules);
    }

    public CheckResult suspectedModules(String... modules) {
        return suspectedModules(Arrays.asList(modules));
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("layer", layer);
        result.put("level", level);
        result.put("name", name);
        result.put("passed", passed);
        result.put("actual", actual);
        result.put("expected", expected);
        result.put("message", message);
        result.put("suspected_modules", suspectedModules);
        return result;
    }

    /** Reads a check back from its JSON projection. */
    public static CheckResult fromJson(Map<String, Object> value) {
        List<String> modules = new ArrayList<>();
        if (value.get("suspected_modules") instanceof List<?> raw) {
            for (Object item : raw) {
                modules.add(String.valueOf(item));
            }
        }
        return new CheckResult(
                String.valueOf(value.getOrDefault("layer", "")),
                String.valueOf(value.getOrDefault("level", "")),
                String.valueOf(value.getOrDefault("name", "")),
                Boolean.TRUE.equals(value.get("passed")),
                value.get("actual"),
                value.get("expected"),
                String.valueOf(value.getOrDefault("message", "")),
                modules);
    }
}
