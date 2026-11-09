package io.assay.opentrace;

import io.assay.json.Json;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.support.AtomicFiles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts exported OpenAI Agents SDK spans into the framework's trace contract.
 *
 * <p>The SDK's export shape drifts: span type and name live either on {@code span_data} or in the
 * OpenInference attributes, timestamps arrive as ISO strings or epoch numbers, and usage hides behind
 * several key spellings. Every one of those alternatives is folded away here so the rest of the
 * framework sees a single {@link NormalizedTrace}.
 */
public final class OpenAiTrace {

    private static final Map<String, String> SPAN_MODULES = spanModules();
    private static final Map<String, String> SPAN_KINDS = spanKinds();
    private static final Map<String, Integer> OUTPUT_PRIORITY = outputPriority();
    private static final DateTimeFormatter OFFSET_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private OpenAiTrace() {
    }

    /** Converts exported spans into one trace, using the default target identity. */
    public static NormalizedTrace normalizeOpenAiTrace(List<Map<String, Object>> spans) {
        return normalizeOpenAiTrace(spans, "openai-agent", "unknown");
    }

    /** Converts exported spans that share one trace id into a single normalized trace. */
    public static NormalizedTrace normalizeOpenAiTrace(
            List<Map<String, Object>> spans, String targetId, String targetVersion) {
        List<Map<String, Object>> items = new ArrayList<>(spans.size());
        for (Map<String, Object> span : spans) {
            items.add(new LinkedHashMap<>(span));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("OpenAI trace requires at least one span");
        }

        Set<String> traceIds = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            traceIds.add(traceId(item));
        }
        traceIds.remove("");
        if (traceIds.size() != 1) {
            throw new IllegalArgumentException("OpenAI trace spans must share exactly one trace_id");
        }
        String traceId = traceIds.iterator().next();

        List<TraceEvent> events = new ArrayList<>();
        List<String> spanTypes = new ArrayList<>();
        List<SpanOutput> outputs = new ArrayList<>();
        List<Double> starts = new ArrayList<>();
        List<Double> ends = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        // Token counts come from generation spans when they exist, falling back to response spans.
        List<Map<String, Object>> tokenSpans = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if ("generation".equals(spanType(item))) {
                tokenSpans.add(item);
            }
        }
        if (tokenSpans.isEmpty()) {
            for (Map<String, Object> item : items) {
                if ("response".equals(spanType(item))) {
                    tokenSpans.add(item);
                }
            }
        }

        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            String spanType = spanType(item);
            spanTypes.add(spanType);
            Map<String, Object> attributes = attributes(item);
            Duration duration = duration(item);
            if (duration.start() != null) {
                starts.add(duration.start());
            }
            if (duration.end() != null) {
                ends.add(duration.end());
            }

            String error = errorMessage(item);
            Map<String, Object> eventFields = new LinkedHashMap<>(attributes);
            eventFields.put("span_type", spanType);
            eventFields.put("span_id", getOr(item, "id", getOr(item, "span_id", "")));
            eventFields.put(
                    "parent_span_id", getOr(item, "parent_id", getOr(item, "parent_span_id", "")));
            events.add(new TraceEvent(
                    SPAN_MODULES.getOrDefault(spanType, "OpenAITrace"),
                    spanName(item, spanType),
                    error != null ? "error" : "ok",
                    duration.milliseconds(),
                    error,
                    eventFields));

            Object output = spanOutput(item, attributes);
            if (output != null) {
                outputs.add(new SpanOutput(OUTPUT_PRIORITY.getOrDefault(spanType, 1), index, output));
            }

            if (truthy(item.get("error")) || statusIsError(item.get("status"))) {
                continue;
            }
            if (tokenSpans.contains(item)) {
                Tokens usage = usage(item, attributes);
                inputTokens += usage.input();
                outputTokens += usage.output();
            }
        }

        int errorCount = 0;
        for (TraceEvent event : events) {
            if ("error".equals(event.status())) {
                errorCount++;
            }
        }
        Map<String, Integer> spanCounts = new LinkedHashMap<>();
        for (String name : spanTypes) {
            spanCounts.merge(name, 1, Integer::sum);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("span_count", items.size());
        fields.put("agent_span_count", spanCounts.getOrDefault("agent", 0));
        fields.put(
                "llm_call_count",
                spanCounts.getOrDefault("generation", 0) + spanCounts.getOrDefault("response", 0));
        fields.put(
                "tool_call_count",
                spanCounts.getOrDefault("function", 0) + spanCounts.getOrDefault("mcp_tools", 0));
        fields.put("handoff_count", spanCounts.getOrDefault("handoff", 0));
        fields.put("error_count", errorCount);
        fields.put("input_tokens", inputTokens);
        fields.put("output_tokens", outputTokens);
        fields.put("total_tokens", inputTokens + outputTokens);
        if (!starts.isEmpty() && !ends.isEmpty()) {
            double maxEnd = ends.get(0);
            for (double value : ends) {
                maxEnd = Math.max(maxEnd, value);
            }
            double minStart = starts.get(0);
            for (double value : starts) {
                minStart = Math.min(minStart, value);
            }
            fields.put("duration_ms", round4((maxEnd - minStart) * 1000));
        }

        Map<String, Object> firstAttributes = attributes(items.get(0));
        putIfPresent(fields, "workflow_name", firstAttributes, "agent.workflow.name");
        putIfPresent(fields, "group_id", firstAttributes, "agent.workflow.group_id");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source", "openai-agents-sdk");
        raw.put("spans", items);

        return new NormalizedTrace(
                traceId,
                finalOutput(outputs),
                events,
                fields,
                feedback(items),
                "agent",
                targetId,
                targetVersion,
                raw);
    }

    /** Reads exported spans from a JSONL file and groups them into traces, using the default identity. */
    public static Map<String, NormalizedTrace> loadOpenAiTraces(Path path) {
        return loadOpenAiTraces(path, "openai-agent", "unknown");
    }

    /** Reads exported spans from a JSONL file and groups them into traces by trace id. */
    public static Map<String, NormalizedTrace> loadOpenAiTraces(
            Path path, String targetId, String targetVersion) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : AtomicFiles.readText(path).split("\n", -1)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> item;
            try {
                Object parsed = Json.parse(line);
                if (!(parsed instanceof Map)) {
                    throw new IllegalArgumentException(
                            "OpenAI trace at " + path + ":" + lineNumber + " must be an object");
                }
                item = asMap(parsed);
            } catch (io.assay.json.JsonException error) {
                throw new IllegalArgumentException(
                        "invalid OpenAI trace at " + path + ":" + lineNumber + ": " + error.getMessage(),
                        error);
            }
            String traceId = traceId(item);
            if (traceId.isEmpty()) {
                throw new IllegalArgumentException(
                        "OpenAI trace at " + path + ":" + lineNumber + " requires trace_id");
            }
            grouped.computeIfAbsent(traceId, key -> new ArrayList<>()).add(item);
        }

        Map<String, NormalizedTrace> traces = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            traces.put(entry.getKey(), normalizeOpenAiTrace(entry.getValue(), targetId, targetVersion));
        }
        return traces;
    }

    private static Map<String, String> spanModules() {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("agent", "OpenAIAgent");
        modules.put("generation", "OpenAILLM");
        modules.put("response", "OpenAILLM");
        modules.put("function", "OpenAITool");
        modules.put("mcp_tools", "OpenAITool");
        modules.put("handoff", "OpenAIHandoff");
        modules.put("guardrail", "OpenAIGuardrail");
        return modules;
    }

    private static Map<String, String> spanKinds() {
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("AGENT", "agent");
        kinds.put("LLM", "generation");
        kinds.put("TOOL", "function");
        kinds.put("GUARDRAIL", "guardrail");
        return kinds;
    }

    private static Map<String, Integer> outputPriority() {
        Map<String, Integer> priority = new LinkedHashMap<>();
        priority.put("agent", 3);
        priority.put("generation", 2);
        priority.put("response", 2);
        return priority;
    }

    private static String traceId(Map<String, Object> item) {
        return textOf(firstTruthy(item.get("trace_id"), item.get("traceId"), "")).strip();
    }

    private static Map<String, Object> spanData(Map<String, Object> item) {
        return asMap(item.get("span_data"));
    }

    private static Map<String, Object> attributes(Map<String, Object> item) {
        return asMap(item.get("attributes"));
    }

    private static String spanType(Map<String, Object> item) {
        Map<String, Object> data = spanData(item);
        if (truthy(data.get("type"))) {
            return textOf(data.get("type")).toLowerCase(Locale.ROOT);
        }
        Map<String, Object> attributes = attributes(item);
        Object value = firstTruthy(
                attributes.get("inference.observation_kind"),
                attributes.get("openinference.span.kind"));
        String kind = textOf(value).toUpperCase(Locale.ROOT);
        return SPAN_KINDS.getOrDefault(kind, "custom");
    }

    private static String spanName(Map<String, Object> item, String spanType) {
        Map<String, Object> attributes = attributes(item);
        return textOf(firstTruthy(
                spanData(item).get("name"),
                item.get("name"),
                attributes.get("sdk.span.name"),
                attributes.get("tool.name"),
                spanType));
    }

    private static Object spanOutput(Map<String, Object> item, Map<String, Object> attributes) {
        Map<String, Object> data = spanData(item);
        // Take the first candidate that is present, not the first that is truthy: an empty string is a
        // real output, and treating it as absent would silently substitute a lower-priority one.
        Object[] candidates = {
            data.get("output"),
            data.get("output_messages"),
            attributes.get("output.value"),
            attributes.get("llm.output_messages"),
            attributes.get("llm.output"),
        };
        for (Object value : candidates) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String errorMessage(Map<String, Object> item) {
        Object error = item.get("error");
        if (error instanceof Map<?, ?>) {
            if (asMap(error).isEmpty()) {
                return null;
            }
            return textOf(firstTruthy(asMap(error).get("message"), "OpenAI span failed"));
        }
        if (truthy(error)) {
            return textOf(error);
        }
        Object status = item.get("status");
        if (status instanceof Map<?, ?> && statusIsError(status)) {
            return textOf(firstTruthy(asMap(status).get("message"), "OpenAI span failed"));
        }
        return null;
    }

    private static boolean statusIsError(Object status) {
        if (!(status instanceof Map<?, ?>)) {
            return false;
        }
        Map<String, Object> map = asMap(status);
        String code = textOf(firstTruthy(map.get("code"), map.get("status"), "")).toUpperCase(Locale.ROOT);
        return code.contains("ERROR") || code.equals("FAILED") || code.equals("FAILURE");
    }

    private static Duration duration(Map<String, Object> item) {
        Object raw = item.get("duration_ms");
        if (raw != null) {
            Double value = toDoubleOrNull(raw);
            if (value != null) {
                return new Duration(value, null, null);
            }
        }
        Double start = timestamp(getOr(item, "started_at", item.get("start_time")));
        Double end = timestamp(getOr(item, "ended_at", item.get("end_time")));
        if (start == null || end == null || end < start) {
            return new Duration(0, start, end);
        }
        return new Duration((end - start) * 1000, start, end);
    }

    private static Double timestamp(Object value) {
        if (value instanceof Number number && !(value instanceof Boolean)) {
            double epoch = number.doubleValue();
            // Values past ten billion are milliseconds; anything smaller is already seconds.
            return epoch > 10_000_000_000d ? epoch / 1000d : epoch;
        }
        if (value == null) {
            return null;
        }
        String text = textOf(value).strip().replace(" T", "T");
        if (text.isEmpty()) {
            return null;
        }
        if (text.endsWith("Z")) {
            text = text.substring(0, text.length() - 1) + "+00:00";
        }
        try {
            return seconds(OffsetDateTime.parse(text));
        } catch (DateTimeParseException noOffset) {
            // Fall through to the instant grammar.
        }
        try {
            return seconds(Instant.parse(text));
        } catch (DateTimeParseException notInstant) {
            try {
                return seconds(OffsetDateTime.parse(text, OFFSET_FORMAT));
            } catch (DateTimeParseException unparseable) {
                return null;
            }
        }
    }

    private static double seconds(OffsetDateTime value) {
        return value.toEpochSecond() + value.getNano() / 1_000_000_000.0;
    }

    private static double seconds(Instant value) {
        return value.getEpochSecond() + value.getNano() / 1_000_000_000.0;
    }

    private static Tokens usage(Map<String, Object> item, Map<String, Object> attributes) {
        Map<String, Object> usage = asMap(spanData(item).get("usage"));
        Object inputValue = getOr(usage, "input_tokens", usage.get("prompt_tokens"));
        Object outputValue = getOr(usage, "output_tokens", usage.get("completion_tokens"));
        if (inputValue == null) {
            inputValue = attributes.get("llm.token_count.prompt");
        }
        if (outputValue == null) {
            outputValue = attributes.get("llm.token_count.completion");
        }
        return new Tokens(toInt(inputValue), toInt(outputValue));
    }

    private static Map<String, Object> feedback(List<Map<String, Object>> items) {
        Map<String, Object> feedback = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            Object value = item.get("feedback");
            if (value instanceof Map<?, ?>) {
                feedback.putAll(asMap(value));
            }
            for (Map.Entry<String, Object> entry : attributes(item).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.startsWith("feedback.")) {
                    feedback.put(key.substring("feedback.".length()), entry.getValue());
                }
            }
        }
        return feedback;
    }

    private static Object finalOutput(List<SpanOutput> outputs) {
        // max() over (priority, index) tuples: highest priority wins, ties broken by the latest index.
        SpanOutput best = null;
        for (SpanOutput candidate : outputs) {
            if (best == null
                    || candidate.priority() > best.priority()
                    || (candidate.priority() == best.priority() && candidate.index() > best.index())) {
                best = candidate;
            }
        }
        return best == null ? null : best.output();
    }

    private static void putIfPresent(
            Map<String, Object> fields, String name, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            fields.put(name, source.get(key));
        }
    }

    private static Double toDoubleOrNull(Object value) {
        if (value instanceof Number number && !(value instanceof Boolean)) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return null;
    }

    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException error) {
                return 0;
            }
        }
        return 0;
    }

    private static double round4(double value) {
        return new BigDecimal(Double.toString(value)).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static Object getOr(Map<String, Object> map, String key, Object fallback) {
        return map.containsKey(key) ? map.get(key) : fallback;
    }

    /** The first truthy operand, or the last one when none is truthy. */
    private static Object firstTruthy(Object... values) {
        if (values.length == 0) {
            return null;
        }
        for (Object value : values) {
            if (truthy(value)) {
                return value;
            }
        }
        return values[values.length - 1];
    }

    /** Truthiness over the JSON-shaped value set. */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return true;
    }

    /** Renders a value as text. */
    private static String textOf(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private record Duration(double milliseconds, Double start, Double end) {
    }

    private record SpanOutput(int priority, int index, Object output) {
    }

    private record Tokens(int input, int output) {
    }
}
