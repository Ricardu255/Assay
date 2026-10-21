package io.assay.rules;

import io.assay.model.Rule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict parsing of the declarative rule arrays used by file-based adapters.
 *
 * <p>Both the HTTP adapter and the trace-file adapter accept the same rule shape. Keeping the
 * validation here means a configuration error fails identically whichever adapter reads it.
 */
public final class RuleConfig {

    private static final Set<String> OPERATORS =
            Set.of("eq", "ne", "contains", "not_contains", "min", "max", "in", "exists",
                    "sequence_contains", "sequence_eq");
    private static final Set<String> OPTIONS =
            Set.of("name", "actual", "operator", "expected", "value", "message", "suspected_modules");

    private RuleConfig() {
    }

    /** Parses one rule array; {@code null} means an empty array. */
    public static List<Rule> parse(Object raw, String key) {
        Object value = raw == null ? List.of() : raw;
        if (!(value instanceof List<?> rows)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        List<Rule> rules = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object row : rows) {
            Map<String, Object> rule = object(row, key + " rule");
            for (String option : rule.keySet()) {
                if (!OPTIONS.contains(option)) {
                    throw new IllegalArgumentException("unknown rule option: " + option);
                }
            }
            String name = text(rule, "name", null);
            String actual = path(rule, "actual", null);
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate rule name in " + key + ": " + name);
            }
            String operator = text(rule, "operator", "eq");
            if (!OPERATORS.contains(operator)) {
                throw new IllegalArgumentException("unsupported rule operator: " + operator);
            }
            if (!"exists".equals(operator) && rule.containsKey("expected") == rule.containsKey("value")) {
                throw new IllegalArgumentException("rule requires exactly one of expected or value: " + name);
            }
            if ("exists".equals(operator) && (rule.containsKey("expected") || rule.containsKey("value"))) {
                throw new IllegalArgumentException("exists rule does not use expected or value: " + name);
            }
            if (operator.startsWith("sequence_") && rule.containsKey("value")) {
                if (!(rule.get("value") instanceof List<?> sequence) || sequence.isEmpty()) {
                    throw new IllegalArgumentException("sequence value must be a non-empty array: " + name);
                }
            }
            List<String> modules = new ArrayList<>();
            Object suspects = rule.getOrDefault("suspected_modules", List.of());
            if (!(suspects instanceof List<?> list)) {
                throw new IllegalArgumentException("suspected_modules must be an array");
            }
            for (Object module : list) {
                if (!(module instanceof String moduleText) || moduleText.isBlank()) {
                    throw new IllegalArgumentException("suspected_modules entries must be non-empty strings");
                }
                modules.add(moduleText);
            }
            if (rule.containsKey("message") && !(rule.get("message") instanceof String)) {
                throw new IllegalArgumentException("message must be a string");
            }
            rules.add(new Rule(
                    name,
                    actual,
                    operator,
                    rule.containsKey("expected") ? path(rule, "expected", null) : null,
                    rule.get("value"),
                    (String) rule.get("message"),
                    modules));
        }
        return List.copyOf(rules);
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.getOrDefault(key, fallback);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-empty string");
        }
        return text;
    }

    private static String path(Map<String, Object> map, String key, String fallback) {
        String value = text(map, key, fallback);
        if (value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)
                || java.util.Arrays.stream(value.split("\\.", -1))
                        .anyMatch(part -> part.isEmpty()
                                || (part.matches("[0-9]+") && part.length() > 9))) {
            throw new IllegalArgumentException(
                    key + " must be a dot-separated path, such as data.answer or choices.0.text");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return (Map<String, Object>) value;
    }
}
