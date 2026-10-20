package io.assay.trace;

import io.assay.json.Json;
import io.assay.json.JsonException;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import io.assay.rules.RuleConfig;
import io.assay.support.AtomicFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates traces exported by another runtime, without a compiled Java adapter.
 *
 * <p>Two input shapes are accepted. {@code normalized} is the JSON projection of
 * {@link NormalizedTrace}; {@code openinference} reads the attribute conventions used by Phoenix and
 * OpenTelemetry exports, including OTLP's attribute arrays and {@code resourceSpans} wrapper. A run is
 * associated with an evaluation case by {@code case_id} (normalized) or an attribute such as
 * {@code assay.case_id} (OpenInference).
 *
 * <p>The adapter is read-only: it never calls an agent, so the CLI must run with
 * {@code --source offline}. A case missing from the export fails with an explicit message rather than
 * silently disappearing from the report.
 */
public final class TraceFileAdapter {

    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final Set<String> OPTIONS = Set.of(
            "type", "name", "format", "runs", "target_type", "target_id", "target_version",
            "case_id_attribute", "hard_gates", "soft_quality");

    private final Map<String, NormalizedTrace> traces;

    private TraceFileAdapter(Map<String, NormalizedTrace> traces) {
        this.traces = traces;
    }

    /** Loads the adapter configuration and the exported runs it points at. */
    public static ProjectAdapter load(Path file) {
        Map<String, Object> config = readObject(file, "adapter configuration");
        for (String key : config.keySet()) {
            if (!OPTIONS.contains(key)) {
                throw new IllegalArgumentException("unknown trace configuration option: " + key);
            }
        }
        if (!"trace_file".equals(config.get("type"))) {
            throw new IllegalArgumentException("trace configuration requires type: trace_file");
        }

        String name = optionalText(config.get("name")) == null ? "trace-file" : optionalText(config.get("name"));
        String format = optionalText(config.get("format")) == null ? "normalized" : optionalText(config.get("format"));
        if (!List.of("normalized", "openinference").contains(format)) {
            throw new IllegalArgumentException("trace configuration format must be normalized or openinference");
        }
        String runsText = optionalText(config.get("runs"));
        if (runsText == null) {
            throw new IllegalArgumentException("trace configuration requires runs");
        }
        Path runs = Path.of(runsText);
        String targetType = optionalText(config.get("target_type"));
        String targetId = optionalText(config.get("target_id"));
        String targetVersion = optionalText(config.get("target_version"));
        String caseIdAttribute = optionalText(config.get("case_id_attribute"));
        if (caseIdAttribute == null) {
            caseIdAttribute = "assay.case_id";
        }

        List<Rule> hard = RuleConfig.parse(config.get("hard_gates"), "hard_gates");
        List<Rule> soft = RuleConfig.parse(config.get("soft_quality"), "soft_quality");

        Map<String, NormalizedTrace> traces = switch (format) {
            case "normalized" -> loadNormalized(runs, targetType, targetId, targetVersion);
            case "openinference" ->
                    loadOpenInference(runs, targetType, targetId, targetVersion, caseIdAttribute);
            default -> throw new IllegalArgumentException("unsupported trace format: " + format);
        };
        TraceFileAdapter adapter = new TraceFileAdapter(traces);
        return new ProjectAdapter(name, adapter::call, adapter::read, hard, soft);
    }

    private Object call(EvalCase evalCase, RunContext context) {
        throw new IllegalStateException("trace_file adapter reads exported traces; pass --source offline");
    }

    private NormalizedTrace read(Object handle, EvalCase evalCase) {
        Object reference = evalCase.metadata().get("trace_ref");
        String key = reference == null ? evalCase.caseId() : String.valueOf(reference);
        NormalizedTrace trace = traces.get(key);
        if (trace == null && !key.equals(evalCase.caseId())) {
            trace = traces.get(evalCase.caseId());
        }
        if (trace == null) {
            throw new IllegalArgumentException(
                    "trace file has no run for case " + key + "; the export contains " + traces.size() + " runs");
        }
        return trace;
    }

    private static Map<String, NormalizedTrace> loadNormalized(
            Path path, String targetType, String targetId, String targetVersion) {
        Map<String, Object> payload = readObject(path, "trace file");
        if (!(payload.get("runs") instanceof List<?> rows)) {
            throw new IllegalArgumentException("normalized trace file requires a runs array: " + path);
        }
        String fallbackVersion = optionalText(payload.get("version"));
        Map<String, NormalizedTrace> traces = new LinkedHashMap<>();
        for (Object rawRun : rows) {
            Map<String, Object> run = asMap(rawRun);
            String caseId = text(first(run.get("case_id"), run.get("id")));
            if (caseId.isBlank()) {
                throw new IllegalArgumentException("normalized run requires a non-empty case_id: " + path);
            }
            if (!(run.get("trace") instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("normalized run " + caseId + " requires a trace object");
            }
            NormalizedTrace trace = NormalizedTrace.fromDict(asMap(run.get("trace")));
            if (trace.traceId().isBlank()) {
                throw new IllegalArgumentException("normalized run " + caseId + " requires a non-empty trace_id");
            }
            NormalizedTrace identified = applyIdentity(
                    trace, targetType, targetId, targetVersion == null ? fallbackVersion : targetVersion);
            if (traces.putIfAbsent(caseId, identified) != null) {
                throw new IllegalArgumentException("normalized trace file contains duplicate case_id: " + caseId);
            }
        }
        if (traces.isEmpty()) {
            throw new IllegalArgumentException("normalized trace file contains no runs: " + path);
        }
        return traces;
    }

    private static Map<String, NormalizedTrace> loadOpenInference(
            Path path,
            String targetType,
            String targetId,
            String targetVersion,
            String caseIdAttribute) {
        Map<String, Object> payload = readObject(path, "trace file");
        List<Map<String, Object>> spans = collectSpans(payload, path);
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> span : spans) {
            String traceId = text(first(span.get("trace_id"), span.get("traceId")));
            if (traceId.isBlank()) {
                throw new IllegalArgumentException("OpenInference span requires a non-empty trace_id: " + path);
            }
            grouped.computeIfAbsent(traceId, key -> new ArrayList<>()).add(span);
        }
        if (grouped.isEmpty()) {
            throw new IllegalArgumentException("OpenInference export contains no spans: " + path);
        }

        String fallbackVersion = optionalText(payload.get("version"));
        Map<String, NormalizedTrace> traces = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> ordered = new ArrayList<>(entry.getValue());
            ordered.sort(Comparator.comparingLong(span -> startNanos(span)));
            String caseId = findCaseId(ordered, caseIdAttribute);
            if (caseId.isBlank()) {
                throw new IllegalArgumentException(
                        "OpenInference trace " + entry.getKey() + " is missing " + caseIdAttribute);
            }
            String version = targetVersion == null ? fallbackVersion : targetVersion;
            NormalizedTrace trace = buildTrace(entry.getKey(), caseId, ordered, targetType, targetId, version);
            if (traces.putIfAbsent(caseId, trace) != null) {
                throw new IllegalArgumentException("OpenInference export contains duplicate case id: " + caseId);
            }
        }
        return traces;
    }

    private static NormalizedTrace buildTrace(
            String traceId,
            String caseId,
            List<Map<String, Object>> spans,
            String targetType,
            String targetId,
            String targetVersion) {
        List<TraceEvent> events = new ArrayList<>(spans.size());
        int promptTokens = 0;
        int completionTokens = 0;
        int errorCount = 0;
        int toolCalls = 0;
        int llmCalls = 0;
        int agentSpans = 0;
        long earliestStart = Long.MAX_VALUE;
        long latestEnd = 0;
        double summedDuration = 0;
        Object lastOutput = null;
        Object agentOutput = null;
        Map<String, Object> feedback = new LinkedHashMap<>();

        for (Map<String, Object> span : spans) {
            Map<String, Object> attributes = attributes(span);
            String kind = spanKind(span, attributes);
            String error = errorMessage(span, attributes);
            if (error != null) {
                errorCount++;
            }
            if ("TOOL".equals(kind)) {
                toolCalls++;
            } else if ("LLM".equals(kind)) {
                llmCalls++;
            } else if ("AGENT".equals(kind)) {
                agentSpans++;
            }
            promptTokens += intValue(attributes.get("llm.token_count.prompt"));
            completionTokens += intValue(attributes.get("llm.token_count.completion"));

            Map<String, Object> eventFields = new LinkedHashMap<>(attributes);
            eventFields.put("span_type", kind);
            eventFields.put("span_id", text(first(span.get("span_id"), span.get("spanId"))));
            eventFields.put("parent_span_id", text(first(span.get("parent_span_id"), span.get("parentSpanId"))));
            Object input = parseJsonValue(attributes.get("input.value"));
            if (input != null) {
                eventFields.put("input", input);
            }
            Object output = parseJsonValue(attributes.get("output.value"));
            if (output != null) {
                eventFields.put("output", output);
            }

            double duration = durationMs(span);
            summedDuration += duration;
            long start = startNanos(span);
            long end = endNanos(span);
            if (start > 0) {
                earliestStart = Math.min(earliestStart, start);
            }
            if (end > 0) {
                latestEnd = Math.max(latestEnd, end);
            }
            if (output != null && !String.valueOf(output).isBlank()) {
                lastOutput = output;
                if ("AGENT".equals(kind) || "CHAIN".equals(kind)) {
                    agentOutput = output;
                }
            }
            events.add(new TraceEvent(
                    moduleFor(kind),
                    spanName(span, attributes, kind),
                    error == null ? "ok" : "error",
                    duration,
                    error,
                    eventFields));

            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (entry.getKey().startsWith("feedback.")) {
                    feedback.putIfAbsent(entry.getKey().substring("feedback.".length()), entry.getValue());
                }
            }
        }

        double traceDuration = summedDuration;
        if (earliestStart != Long.MAX_VALUE && latestEnd >= earliestStart) {
            traceDuration = (latestEnd - earliestStart) / 1_000_000.0;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("span_count", spans.size());
        fields.put("agent_span_count", agentSpans);
        fields.put("llm_call_count", llmCalls);
        fields.put("tool_call_count", toolCalls);
        fields.put("error_count", errorCount);
        fields.put("input_tokens", promptTokens);
        fields.put("output_tokens", completionTokens);
        fields.put("total_tokens", promptTokens + completionTokens);
        fields.put("duration_ms", traceDuration);
        fields.put("case_id", caseId);
        for (Map<String, Object> span : spans) {
            Map<String, Object> attributes = attributes(span);
            Object workflow = first(attributes.get("agent.workflow.name"), attributes.get("workflow.name"));
            if (workflow != null) {
                fields.put("workflow_name", workflow);
                break;
            }
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source", "openinference");
        raw.put("spans", spans);

        return new NormalizedTrace(
                traceId,
                agentOutput != null ? agentOutput : lastOutput,
                events,
                fields,
                feedback,
                targetType == null ? "agent" : targetType,
                targetId == null ? "openinference" : targetId,
                targetVersion == null ? "unknown" : targetVersion,
                raw);
    }

    private static NormalizedTrace applyIdentity(
            NormalizedTrace trace, String targetType, String targetId, String targetVersion) {
        if (targetType == null && targetId == null && targetVersion == null) {
            return trace;
        }
        return new NormalizedTrace(
                trace.traceId(),
                trace.finalOutput(),
                trace.events(),
                trace.fields(),
                trace.feedback(),
                targetType == null ? trace.targetType() : targetType,
                targetId == null ? trace.targetId() : targetId,
                targetVersion == null ? trace.targetVersion() : targetVersion,
                trace.raw());
    }

    private static List<Map<String, Object>> collectSpans(Map<String, Object> payload, Path path) {
        if (payload.get("spans") instanceof List<?> rows) {
            return maps(rows);
        }
        if (payload.get("traces") instanceof List<?> rows) {
            List<Map<String, Object>> spans = new ArrayList<>();
            for (Object rawTrace : rows) {
                Map<String, Object> trace = asMap(rawTrace);
                Object traceId = first(trace.get("trace_id"), trace.get("traceId"));
                for (Object rawSpan : asList(trace.get("spans"))) {
                    Map<String, Object> span = new LinkedHashMap<>(asMap(rawSpan));
                    if (traceId != null && !span.containsKey("trace_id") && !span.containsKey("traceId")) {
                        span.put("trace_id", traceId);
                    }
                    spans.add(span);
                }
            }
            return spans;
        }
        if (payload.get("resourceSpans") instanceof List<?> rows) {
            List<Map<String, Object>> spans = new ArrayList<>();
            for (Object rawResource : rows) {
                Map<String, Object> resource = asMap(rawResource);
                Object scopes = resource.get("scopeSpans");
                if (scopes == null) {
                    scopes = resource.get("instrumentationLibrarySpans");
                }
                for (Object rawScope : asList(scopes)) {
                    for (Object rawSpan : asList(asMap(rawScope).get("spans"))) {
                        spans.add(asMap(rawSpan));
                    }
                }
            }
            return spans;
        }
        throw new IllegalArgumentException(
                "OpenInference export requires spans, traces, or resourceSpans: " + path);
    }

    private static String findCaseId(List<Map<String, Object>> spans, String attribute) {
        for (Map<String, Object> span : spans) {
            Map<String, Object> attributes = attributes(span);
            Object value = attributes.get(attribute);
            if (value == null && !"trajectiq.task_id".equals(attribute)) {
                value = attributes.get("trajectiq.task_id");
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static Map<String, Object> attributes(Map<String, Object> span) {
        Object raw = span.get("attributes");
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : asMap(raw).entrySet()) {
                attributes.put(entry.getKey(), otlpValue(entry.getValue()));
            }
            return attributes;
        }
        if (raw instanceof List<?> rows) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (Object row : rows) {
                Map<String, Object> item = asMap(row);
                Object key = item.get("key");
                if (key != null) {
                    attributes.put(String.valueOf(key), otlpValue(item.get("value")));
                }
            }
            return attributes;
        }
        return Map.of();
    }

    /** Unwraps OTLP's {@code AnyValue} object into the plain value it carries. */
    private static Object otlpValue(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return value;
        }
        Map<String, Object> item = asMap(value);
        if (item.containsKey("stringValue")) {
            return item.get("stringValue");
        }
        if (item.containsKey("boolValue")) {
            return item.get("boolValue");
        }
        if (item.containsKey("intValue")) {
            return parseLongOrString(item.get("intValue"));
        }
        if (item.containsKey("doubleValue")) {
            return item.get("doubleValue");
        }
        if (item.containsKey("bytesValue")) {
            return item.get("bytesValue");
        }
        if (item.get("arrayValue") instanceof Map<?, ?> array) {
            List<Object> values = new ArrayList<>();
            for (Object entry : asList(asMap(array).get("values"))) {
                values.add(otlpValue(entry));
            }
            return values;
        }
        if (item.get("kvlistValue") instanceof Map<?, ?> kvlist) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Object entry : asList(asMap(kvlist).get("values"))) {
                Map<String, Object> pair = asMap(entry);
                Object key = pair.get("key");
                if (key != null) {
                    values.put(String.valueOf(key), otlpValue(pair.get("value")));
                }
            }
            return values;
        }
        return value;
    }

    private static Object parseLongOrString(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static String spanKind(Map<String, Object> span, Map<String, Object> attributes) {
        Object value = first(
                attributes.get("openinference.span.kind"),
                attributes.get("inference.observation_kind"),
                span.get("kind"),
                span.get("span_kind"),
                span.get("type"));
        String kind = text(value).toUpperCase(Locale.ROOT);
        return kind.isEmpty() ? "CHAIN" : kind;
    }

    private static String spanName(
            Map<String, Object> span, Map<String, Object> attributes, String kind) {
        Object value = first(attributes.get("tool.name"), attributes.get("llm.model_name"), span.get("name"));
        return value == null ? kind : String.valueOf(value);
    }

    private static String moduleFor(String kind) {
        return switch (kind) {
            case "TOOL" -> "Tool";
            case "LLM" -> "LLM";
            case "AGENT" -> "Agent";
            case "CHAIN" -> "Chain";
            case "GUARDRAIL" -> "Guardrail";
            case "RETRIEVER" -> "Retriever";
            case "EMBEDDING" -> "Embedding";
            default -> "OpenInference";
        };
    }

    private static String errorMessage(Map<String, Object> span, Map<String, Object> attributes) {
        Object direct = first(attributes.get("error.type"), attributes.get("error.message"));
        if (direct != null) {
            return String.valueOf(direct);
        }
        Object status = span.get("status");
        if (status instanceof Map<?, ?>) {
            Map<String, Object> statusMap = asMap(status);
            String code = text(statusMap.get("code")).toUpperCase(Locale.ROOT);
            if ("2".equals(code) || code.contains("ERROR")) {
                Object message = first(statusMap.get("message"), span.get("status_message"));
                return message == null ? "span_error" : String.valueOf(message);
            }
        }
        Object statusCode = first(span.get("status_code"), span.get("statusCode"), attributes.get("otel.status_code"));
        String code = text(statusCode).toUpperCase(Locale.ROOT);
        if (code.contains("ERROR") || "2".equals(code)) {
            Object message = first(span.get("status_message"), attributes.get("otel.status_description"));
            return message == null ? "span_error" : String.valueOf(message);
        }
        return null;
    }

    private static double durationMs(Map<String, Object> span) {
        Double explicit = number(span.get("duration_ms"));
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        long start = startNanos(span);
        long end = endNanos(span);
        return start > 0 && end >= start ? (end - start) / 1_000_000.0 : 0;
    }

    private static Double number(Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                double parsed = Double.parseDouble(text.trim());
                return Double.isFinite(parsed) ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static long startNanos(Map<String, Object> span) {
        return timestampNanos(first(
                span.get("start_time_unix_nano"),
                span.get("startTimeUnixNano"),
                span.get("start_time"),
                span.get("startTime"),
                span.get("started_at")));
    }

    private static long endNanos(Map<String, Object> span) {
        return timestampNanos(first(
                span.get("end_time_unix_nano"),
                span.get("endTimeUnixNano"),
                span.get("end_time"),
                span.get("endTime"),
                span.get("ended_at")));
    }

    /**
     * Normalizes a timestamp to nanoseconds. Numbers are scaled by magnitude (nanoseconds,
     * milliseconds, then seconds); ISO strings are parsed as instants.
     */
    private static long timestampNanos(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return scaleNanos(number.doubleValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return scaleNanos(Double.parseDouble(text));
        } catch (NumberFormatException ignored) {
            // Fall through to ISO parsing.
        }
        try {
            Instant instant = Instant.parse(text);
            return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        } catch (DateTimeParseException ignored) {
            // Fall through to the offset grammar.
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(text);
            return parsed.toEpochSecond() * 1_000_000_000L + parsed.getNano();
        } catch (DateTimeParseException ignored) {
            return 0;
        }
    }

    private static long scaleNanos(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            return 0;
        }
        if (value > 1e15) {
            return (long) value;
        }
        if (value > 1e12) {
            return (long) (value * 1_000_000);
        }
        if (value > 1e9) {
            return (long) (value * 1_000_000_000);
        }
        return (long) value;
    }

    private static Object parseJsonValue(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return value;
        }
        try {
            return Json.parse(trimmed);
        } catch (JsonException ignored) {
            return value;
        }
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static Map<String, Object> readObject(Path path, String label) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException(label + " does not exist: " + path);
            }
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw new IllegalArgumentException(label + " exceeds 64 MiB: " + path);
            }
            Object parsed = Json.parse(AtomicFiles.readText(path));
            if (!(parsed instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(label + " must contain a JSON object: " + path);
            }
            return asMap(parsed);
        } catch (JsonException error) {
            throw new IllegalArgumentException("invalid JSON in " + label + " " + path + ": " + error.getMessage(), error);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + label + ": " + path, error);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Object first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> maps(List<?> rows) {
        List<Map<String, Object>> maps = new ArrayList<>(rows.size());
        for (Object row : rows) {
            maps.add(asMap(row));
        }
        return maps;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
