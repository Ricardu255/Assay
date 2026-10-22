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
        SequenceResult sequenceResult = isSequence(rule.operator())
                ? sequence(rule.operator(), actual, expected)
                : null;
        boolean passed = sequenceResult != null ? sequenceResult.passed() : matches(rule.operator(), actual, expected);
        Object actualValue = sequenceResult != null
                ? sequenceResult.actual()
                : actual == ValueOps.MISSING ? null : actual;
        Object expectedValue = sequenceResult != null
                ? sequenceResult.expected()
                : expected == ValueOps.MISSING ? null : expected;
        String message =
                !rule.message().isEmpty()
                        ? rule.message()
                        : passed
                                ? "passed"
                                : sequenceResult != null
                                        ? sequenceResult.message()
                                        : rule.actual() + " failed " + rule.operator();
        return new CheckResult(
                "structure",
                level,
                rule.name(),
                passed,
                actualValue,
                expectedValue,
                message,
                rule.suspectedModules());
    }

    private static boolean isSequence(String operator) {
        return "sequence_contains".equals(operator) || "sequence_eq".equals(operator);
    }

    private static SequenceResult sequence(String operator, Object actual, Object expected) {
        if (!(actual instanceof List<?> actualItems)) {
            return new SequenceResult(false, "expected a sequence for " + operator, List.of(), List.of());
        }
        List<StepSpec> actualSteps;
        List<StepSpec> expectedSteps;
        try {
            actualSteps = parseActual(actualItems);
            expectedSteps = parseExpected(expected);
        } catch (IllegalArgumentException error) {
            return new SequenceResult(false, error.getMessage(), List.of(), List.of());
        }
        if (expectedSteps.isEmpty()) {
            return new SequenceResult(false, "expected sequence must be a non-empty array", evidence(actualSteps), List.of());
        }
        List<StepSpec> flatExpected = flatten(expectedSteps);
        if ("sequence_eq".equals(operator)) {
            for (int index = 0; index < Math.max(actualSteps.size(), flatExpected.size()); index++) {
                if (index >= actualSteps.size()) {
                    return new SequenceResult(
                            false, "missing step " + (index + 1) + ": " + label(flatExpected.get(index)),
                            evidence(actualSteps), evidence(expectedSteps));
                }
                if (index >= flatExpected.size()) {
                    return new SequenceResult(
                            false, "unexpected step " + (index + 1) + ": " + label(actualSteps.get(index)),
                            evidence(actualSteps), evidence(expectedSteps));
                }
                String mismatch = mismatch(actualSteps.get(index), flatExpected.get(index), index + 1);
                if (mismatch != null) {
                    return new SequenceResult(false, mismatch, evidence(actualSteps), evidence(expectedSteps));
                }
            }
            return new SequenceResult(true, "passed", evidence(actualSteps), evidence(expectedSteps));
        }
        int cursor = 0;
        for (StepSpec step : expectedSteps) {
            if (step.group() != null) {
                int end = matchGroup(actualSteps, cursor, step.group());
                if (end < 0) {
                    return new SequenceResult(
                            false, "missing ordered group: " + groupLabel(step.group()),
                            evidence(actualSteps), evidence(expectedSteps));
                }
                cursor = end;
                continue;
            }
            int found = cursor;
            while (found < actualSteps.size() && !stepMatches(actualSteps.get(found), step)) {
                found++;
            }
            if (found >= actualSteps.size()) {
                return new SequenceResult(
                        false, "missing ordered step: " + label(step),
                        evidence(actualSteps), evidence(expectedSteps));
            }
            cursor = found + 1;
        }
        return new SequenceResult(true, "passed", evidence(actualSteps), evidence(expectedSteps));
    }

    private static List<StepSpec> parseActual(List<?> items) {
        List<StepSpec> steps = new ArrayList<>();
        for (Object item : items) {
            steps.add(parseStep(item));
        }
        return steps;
    }

    private static List<StepSpec> parseExpected(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException("expected sequence must be a non-empty array");
        }
        List<StepSpec> steps = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof List<?> group) {
                if (group.isEmpty()) {
                    throw new IllegalArgumentException("nested sequence must be a non-empty array");
                }
                steps.add(new StepSpec(null, Map.of(), parseExpected(group)));
            } else {
                steps.add(parseStep(item));
            }
        }
        return steps;
    }

    private static StepSpec parseStep(Object value) {
        if (value instanceof String text) {
            if (text.isBlank()) {
                throw new IllegalArgumentException("sequence step must be a non-empty string");
            }
            return new StepSpec(text, Map.of(), null);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("sequence step must be a string, object, or nested array");
        }
        String key = stepKey(map);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("sequence step requires module/action, step, or tool");
        }
        Object rawArgs = map.containsKey("args") ? map.get("args") : map.get("fields");
        Map<String, Object> args = new LinkedHashMap<>();
        if (rawArgs != null) {
            if (!(rawArgs instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("sequence step args must be an object");
            }
            for (Map.Entry<?, ?> entry : fields.entrySet()) {
                args.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return new StepSpec(key, args, null);
    }

    private static String stepKey(Map<?, ?> map) {
        Object module = map.get("module");
        Object action = map.get("action");
        if (module != null || action != null) {
            return (module == null ? "" : String.valueOf(module)) + "/"
                    + (action == null ? "" : String.valueOf(action));
        }
        Object step = map.get("step");
        if (step != null) {
            return String.valueOf(step);
        }
        Object tool = map.get("tool");
        return tool == null ? null : String.valueOf(tool);
    }

    private static List<StepSpec> flatten(List<StepSpec> steps) {
        List<StepSpec> result = new ArrayList<>();
        for (StepSpec step : steps) {
            if (step.group() == null) {
                result.add(step);
            } else {
                result.addAll(flatten(step.group()));
            }
        }
        return result;
    }

    private static List<Object> evidence(List<StepSpec> steps) {
        List<Object> result = new ArrayList<>();
        for (StepSpec step : steps) {
            if (step.group() != null) {
                result.add(evidence(step.group()));
            } else if (step.args().isEmpty()) {
                result.add(step.key());
            } else {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("step", step.key());
                item.put("args", step.args());
                result.add(item);
            }
        }
        return result;
    }

    private static String label(StepSpec step) {
        return step.args().isEmpty() ? step.key() : step.key() + " " + step.args();
    }

    private static String groupLabel(List<StepSpec> group) {
        List<String> labels = new ArrayList<>();
        for (StepSpec step : group) {
            labels.add(label(step));
        }
        return labels.toString();
    }

    private record StepSpec(String key, Map<String, Object> args, List<StepSpec> group) {
    }

    private static boolean stepMatches(StepSpec actual, StepSpec expected) {
        return actual.key().equals(expected.key()) && argsMatch(actual.args(), expected.args());
    }

    private static boolean argsMatch(Map<String, Object> actual, Map<String, Object> expected) {
        if (expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object value = ValueOps.readPath(actual, entry.getKey());
            if (value == ValueOps.MISSING || !ValueOps.equal(value, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String mismatch(StepSpec actual, StepSpec expected, int position) {
        if (!actual.key().equals(expected.key())) {
            return "step " + position + " expected " + label(expected) + " but was " + label(actual);
        }
        if (!argsMatch(actual.args(), expected.args())) {
            return "step " + position + " " + expected.key() + " args mismatch: expected "
                    + expected.args() + " but was " + actual.args();
        }
        return null;
    }

    private static int matchGroup(List<StepSpec> actual, int cursor, List<StepSpec> group) {
        for (int start = cursor; start < actual.size(); start++) {
            int position = start;
            boolean matched = true;
            for (StepSpec step : group) {
                if (step.group() != null) {
                    int end = matchGroup(actual, position, step.group());
                    if (end < 0) {
                        matched = false;
                        break;
                    }
                    position = end;
                } else if (position < actual.size() && stepMatches(actual.get(position), step)) {
                    position++;
                } else {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return position;
            }
        }
        return -1;
    }

    private record SequenceResult(boolean passed, String message, Object actual, Object expected) {
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
