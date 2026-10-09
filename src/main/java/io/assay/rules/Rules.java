package io.assay.rules;

import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.Rule;
import io.assay.model.TraceEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four evaluation layers.
 *
 * <p>Structure and behavior checks are deterministic gates; consistency and feedback checks only
 * produce human-review candidates. Nothing here reads configuration, a database, or an LLM, so the
 * layer keeps working when those are absent.
 */
public final class Rules {

    private Rules() {
    }

    /** Evaluates one declared rule against a case and its trace. */
    public static CheckResult evaluateRule(Rule rule, String level, EvalCase evalCase, NormalizedTrace trace) {
        Object traceData = trace.toJson();
        Object actual = ValueOps.readPath(traceData, rule.actual());
        Object expected =
                rule.expected() != null
                        ? ValueOps.readPath(evalCase.expected(), rule.expected())
                        : rule.value();
        boolean passed = matches(rule.operator(), actual, expected);
        String message =
                !rule.message().isEmpty()
                        ? rule.message()
                        : passed ? "passed" : rule.actual() + " failed " + rule.operator();
        return new CheckResult(
                "structure",
                level,
                rule.name(),
                passed,
                actual == ValueOps.MISSING ? null : actual,
                expected == ValueOps.MISSING ? null : expected,
                message,
                rule.suspectedModules());
    }

    /** An unsupported operator is a programming error; a type mismatch is a failed check. */
    private static boolean matches(String operator, Object actual, Object expected) {
        if ("exists".equals(operator)) {
            return actual != ValueOps.MISSING && actual != null;
        }
        if (actual == ValueOps.MISSING) {
            return false;
        }
        switch (operator) {
            case "eq":
                return ValueOps.equal(actual, expected);
            case "ne":
                return !ValueOps.equal(actual, expected);
            case "contains": {
                Boolean result = ValueOps.contains(actual, expected);
                return Boolean.TRUE.equals(result);
            }
            case "not_contains": {
                Boolean result = ValueOps.contains(actual, expected);
                return Boolean.FALSE.equals(result);
            }
            case "max": {
                Boolean result = ValueOps.lessOrEqual(actual, expected);
                return Boolean.TRUE.equals(result);
            }
            case "min": {
                Boolean result = ValueOps.greaterOrEqual(actual, expected);
                return Boolean.TRUE.equals(result);
            }
            case "in": {
                Boolean result = ValueOps.contains(expected, actual);
                return Boolean.TRUE.equals(result);
            }
            default:
                throw new IllegalArgumentException("unsupported rule operator: " + operator);
        }
    }

    /** Trace presence plus every declared hard gate and soft quality rule. */
    public static List<CheckResult> structureChecks(
            EvalCase evalCase,
            NormalizedTrace trace,
            List<Rule> hardGates,
            List<Rule> softQuality) {
        List<CheckResult> checks = new ArrayList<>();
        checks.add(
                new CheckResult(
                        "structure",
                        "hard",
                        "trace_id",
                        trace.traceId() != null && !trace.traceId().isEmpty(),
                        trace.traceId(),
                        "non-empty"));
        checks.add(
                new CheckResult(
                        "structure",
                        "hard",
                        "final_output",
                        trace.finalOutput() != null,
                        trace.finalOutput(),
                        "not null"));
        for (Rule rule : hardGates) {
            checks.add(evaluateRule(rule, "hard", evalCase, trace));
        }
        for (Rule rule : softQuality) {
            checks.add(evaluateRule(rule, "soft", evalCase, trace));
        }
        return checks;
    }

    /** Step count, retries, latency, repeated events, and module errors. */
    public static List<CheckResult> behaviorChecks(EvalCase evalCase, NormalizedTrace trace) {
        Map<String, Object> constraints = asMap(evalCase.metadata().get("system_constraints"));
        List<TraceEvent> events = trace.events();
        List<CheckResult> checks = new ArrayList<>();

        if (constraints.containsKey("max_steps")) {
            Object limit = constraints.get("max_steps");
            checks.add(
                    new CheckResult(
                            "behavior",
                            "hard",
                            "max_steps",
                            numeric(events.size()) <= numeric(limit),
                            events.size(),
                            limit));
        }

        int retries = 0;
        for (TraceEvent event : events) {
            if (event.action() != null && event.action().toLowerCase().contains("retry")) {
                retries++;
            }
        }
        if (constraints.containsKey("max_retries")) {
            Object limit = constraints.get("max_retries");
            checks.add(
                    new CheckResult(
                            "behavior", "hard", "max_retries", numeric(retries) <= numeric(limit), retries, limit));
        }

        double latency = 0;
        for (TraceEvent event : events) {
            latency += event.durationMs();
        }
        if (constraints.containsKey("max_latency_ms")) {
            Object limit = constraints.get("max_latency_ms");
            checks.add(
                    new CheckResult(
                            "behavior",
                            "hard",
                            "max_latency_ms",
                            latency <= numeric(limit),
                            latency,
                            limit));
        }

        int maxRepeats = intOr(constraints.get("max_consecutive_repeats"), 3);
        int longest = 0;
        String repeatedModule = null;
        String repeatedAction = null;
        String lastModule = null;
        String lastAction = null;
        int current = 0;
        for (TraceEvent event : events) {
            boolean same = event.module().equals(lastModule) && event.action().equals(lastAction);
            current = same ? current + 1 : 1;
            if (current > longest) {
                longest = current;
                repeatedModule = event.module();
                repeatedAction = event.action();
            }
            lastModule = event.module();
            lastAction = event.action();
        }
        if (longest > maxRepeats) {
            List<String> module = repeatedModule == null || repeatedModule.isEmpty()
                    ? List.of()
                    : List.of(repeatedModule);
            checks.add(
                    new CheckResult(
                                    "behavior",
                                    "candidate",
                                    "repeated_event",
                                    false,
                                    longest,
                                    maxRepeats,
                                    "repeated module/action: ('" + repeatedModule + "', '" + repeatedAction + "')")
                            .suspectedModules(module));
        }

        Map<String, Integer> errors = new LinkedHashMap<>();
        for (TraceEvent event : events) {
            if (event.error() != null || "error".equals(event.status())) {
                errors.merge(event.module(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : errors.entrySet()) {
            checks.add(
                    new CheckResult(
                                    "behavior",
                                    "candidate",
                                    "module_error:" + entry.getKey(),
                                    false,
                                    entry.getValue(),
                                    0,
                                    "trace contains module errors")
                            .suspectedModules(entry.getKey()));
        }
        return checks;
    }

    /** Shadow runs disagree; the difference is a human-review candidate, never an automatic verdict. */
    public static List<CheckResult> consistencyChecks(List<NormalizedTrace> traces, List<Rule> rules) {
        if (traces.size() < 2) {
            return List.of();
        }
        List<CheckResult> checks = new ArrayList<>();
        for (Rule rule : rules) {
            List<Object> values = new ArrayList<>(traces.size());
            for (NormalizedTrace trace : traces) {
                values.add(ValueOps.readPath(trace.toJson(), rule.actual()));
            }
            boolean inconsistent = false;
            for (int index = 1; index < values.size(); index++) {
                if (!ValueOps.equal(values.get(index), values.get(0))) {
                    inconsistent = true;
                    break;
                }
            }
            if (inconsistent) {
                List<Object> actual = new ArrayList<>(values.size());
                for (Object value : values) {
                    actual.add(value == ValueOps.MISSING ? null : value);
                }
                checks.add(
                        new CheckResult(
                                        "consistency",
                                        "candidate",
                                        "inconsistent:" + rule.name(),
                                        false,
                                        actual,
                                        "stable values",
                                        "shadow runs disagree; human review required")
                                .suspectedModules(rule.suspectedModules()));
            }
        }
        return checks;
    }

    /** Implicit feedback signals only ever become candidates. */
    public static List<CheckResult> feedbackChecks(NormalizedTrace trace) {
        Map<String, String> signals = new LinkedHashMap<>();
        signals.put("explicit_negative", "user explicitly rejected the result");
        signals.put("repeated_question", "user repeated a semantically similar question");
        signals.put("rephrased", "user immediately rephrased the request");
        List<CheckResult> checks = new ArrayList<>();
        for (Map.Entry<String, String> entry : signals.entrySet()) {
            if (Boolean.TRUE.equals(trace.feedback().get(entry.getKey()))) {
                checks.add(
                        new CheckResult(
                                "feedback", "candidate", entry.getKey(), false, true, false, entry.getValue()));
            }
        }
        return checks;
    }

    private static double numeric(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        return Double.NaN;
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
