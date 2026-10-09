package io.assay.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.assay.support.Immutable;

/**
 * One declarative gate or quality rule.
 *
 * <p>The comparison operand comes from either {@code expected} — a path read out of the case's
 * {@code expected} block — or {@code value}, a literal. Exactly one of them is normally set.
 */
public record Rule(
        String name,
        String actual,
        String operator,
        String expected,
        Object value,
        String message,
        List<String> suspectedModules) {

    public Rule {
        operator = operator == null ? "eq" : operator;
        message = message == null ? "" : message;
        suspectedModules = suspectedModules == null ? List.of() : Immutable.list(suspectedModules);
    }

    public static Rule of(String name, String actual, String expectedPath) {
        return new Rule(name, actual, "eq", expectedPath, null, "", List.of());
    }

    public static Rule of(String name, String actual, String operator, String expectedPath) {
        return new Rule(name, actual, operator, expectedPath, null, "", List.of());
    }

    public static Rule ofValue(String name, String actual, String operator, Object value) {
        return new Rule(name, actual, operator, null, value, "", List.of());
    }

    public static Rule exists(String name, String actual) {
        return new Rule(name, actual, "exists", null, null, "", List.of());
    }

    public Rule suspectedModules(String... modules) {
        return new Rule(name, actual, operator, expected, value, message, Arrays.asList(modules));
    }

    public Rule suspectedModules(List<String> modules) {
        return new Rule(name, actual, operator, expected, value, message, new ArrayList<>(modules));
    }

    public Rule message(String text) {
        return new Rule(name, actual, operator, expected, value, text, suspectedModules);
    }
}
