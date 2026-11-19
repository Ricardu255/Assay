package io.assay.metrics;

import io.assay.json.Json;
import io.assay.model.MetricResult;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detects agent loops without calling a model.
 *
 * <p>Three independent signals are combined by weight: identical tool calls (0.40), adjacent model
 * outputs that barely change (0.35), and a repeated signature block such as {@code A,B,A,B} (0.25).
 * Signals that the trace cannot support are marked unobserved and the remaining weights are
 * renormalized; a trace with no usable evidence at all is skipped, never scored 1.0.
 */
public final class LoopDetectionMetric implements Metric {

    /** Stable metric name. */
    public static final String NAME = "loop_detection";

    private static final List<String> TOOL_NAME_FIELDS = List.of("tool_call_name", "tool_name", "name", "tool");
    private static final List<String> TOOL_ARGUMENT_FIELDS =
            List.of("arguments", "args", "input", "parameters", "tool_arguments");
    private static final List<String> MODEL_OUTPUT_FIELDS =
            List.of("output", "response", "content", "text", "reply", "message");
    private static final int MIN_WORDS = 8;
    private static final int MAX_WORDS = 200;
    private static final double STALL_THRESHOLD = 0.85;
    private static final double STALL_ZERO_THRESHOLD = 0.95;

    private final double threshold;

    /** Creates a metric with the default threshold of 0.9. */
    public LoopDetectionMetric() {
        this(0.9);
    }

    public LoopDetectionMetric(double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("loop_detection threshold must be finite and between 0 and 1");
        }
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String defaultLevel() {
        return "soft";
    }

    @Override
    public String fingerprint() {
        return "loop-detection-v1;threshold=" + threshold;
    }

    @Override
    public MetricResult measure(MetricContext context) {
        NormalizedTrace trace = context.trace();
        if (trace == null) {
            return MetricResult.skip(NAME, threshold, "no trace");
        }
        List<TraceEvent> events = trace.events();

        ToolSignal tools = toolSignal(events);
        StallSignal stall = stallSignal(events);
        String cycle = cycle(events);

        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        if (tools.observed) {
            weights.put("tool_repetition", 0.40);
            scores.put("tool_repetition", tools.score);
        }
        if (stall.observed) {
            weights.put("reasoning_stall", 0.35);
            scores.put("reasoning_stall", stall.score);
        }
        if (cycle != null) {
            weights.put("repeated_cycle", 0.25);
            scores.put("repeated_cycle", 0.0);
        }
        if (weights.isEmpty()) {
            return MetricResult.skip(NAME, threshold, "no loop evidence");
        }

        double totalWeight = 0;
        double weighted = 0;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            totalWeight += entry.getValue();
            weighted += entry.getValue() * scores.get(entry.getKey());
        }
        double score = weighted / totalWeight;

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("tool_calls", tools.calls);
        breakdown.put("max_tool_repetition", tools.maxRepetition);
        breakdown.put("repeated_tools", new ArrayList<>(tools.repeatedTools));
        breakdown.put("model_outputs", stall.comparedOutputs);
        breakdown.put("max_similarity", stall.maxSimilarity);
        breakdown.put("cycle", cycle == null ? "" : cycle);
        breakdown.put("signals", scores);
        breakdown.put("weights", weights);

        List<Map<String, Object>> evidence = new ArrayList<>();
        if (tools.maxRepetition >= 3) {
            evidence.add(evidenceRow(
                    "tool_repetition", tools.maxRepetition + " identical tool calls: " + tools.repeatedTools));
        }
        if (stall.observed && stall.maxSimilarity >= STALL_THRESHOLD) {
            evidence.add(evidenceRow(
                    "reasoning_stall", String.format(Locale.ROOT, "adjacent model outputs are %.3f similar",
                            stall.maxSimilarity)));
        }
        if (cycle != null) {
            evidence.add(evidenceRow("repeated_cycle", cycle));
        }

        String reason = score >= threshold
                ? "no loop signal above threshold"
                : "loop signals: " + String.join(", ", scores.keySet());
        MetricResult result = score >= threshold
                ? MetricResult.pass(NAME, score, threshold, reason)
                : MetricResult.fail(NAME, score, threshold, reason);
        return result.withDetails(breakdown, evidence);
    }

    private static ToolSignal toolSignal(List<TraceEvent> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int calls = 0;
        for (TraceEvent event : events) {
            if (!"tool".equals(event.module()) || !"call_start".equals(event.action())) {
                continue;
            }
            calls++;
            String name = field(event, TOOL_NAME_FIELDS);
            String arguments = arguments(event);
            String signature = (name.isEmpty() ? "(unnamed)" : name) + "|" + arguments;
            counts.merge(signature, 1, Integer::sum);
        }
        if (calls == 0) {
            return new ToolSignal(false, 1.0, 0, 0, Set.of());
        }
        int max = 0;
        Set<String> repeated = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            max = Math.max(max, entry.getValue());
            if (entry.getValue() >= 3) {
                repeated.add(entry.getKey().substring(0, entry.getKey().indexOf('|')));
            }
        }
        double score = max >= 6 ? 0.0 : max >= 3 ? 0.5 : 1.0;
        return new ToolSignal(true, score, calls, max, repeated);
    }

    private static StallSignal stallSignal(List<TraceEvent> events) {
        List<List<String>> outputs = new ArrayList<>();
        for (TraceEvent event : events) {
            if (!"model".equals(event.module())) {
                continue;
            }
            String text = field(event, MODEL_OUTPUT_FIELDS);
            if (!text.isEmpty()) {
                outputs.add(words(text));
            }
        }
        int compared = 0;
        double max = 0;
        List<String> previous = null;
        for (List<String> current : outputs) {
            if (previous != null && previous.size() >= MIN_WORDS && current.size() >= MIN_WORDS) {
                max = Math.max(max, similarity(previous, current));
                compared++;
            }
            previous = current;
        }
        if (compared == 0) {
            return new StallSignal(false, 1.0, 0, 0);
        }
        double score = max >= STALL_ZERO_THRESHOLD ? 0.0 : max >= STALL_THRESHOLD ? 0.5 : 1.0;
        return new StallSignal(true, score, compared, max);
    }

    private static String cycle(List<TraceEvent> events) {
        List<String> signatures = new ArrayList<>();
        for (TraceEvent event : events) {
            if (!isStartEvent(event)) {
                continue;
            }
            signatures.add(signature(event));
        }
        for (int start = 0; start < signatures.size(); start++) {
            for (int period = 2; period <= 6 && start + 2 * period <= signatures.size(); period++) {
                if (signatures.subList(start, start + period)
                        .equals(signatures.subList(start + period, start + 2 * period))) {
                    return String.join(" -> ", signatures.subList(start, start + period));
                }
            }
        }
        return null;
    }

    private static boolean isStartEvent(TraceEvent event) {
        if ("subagent_exposed".equals(event.action())) {
            return true;
        }
        return ("tool".equals(event.module()) || "model".equals(event.module()) || "agent".equals(event.module()))
                && ("call_start".equals(event.action()) || "start".equals(event.action()));
    }

    private static String signature(TraceEvent event) {
        String identity = field(event, List.of("tool_call_name", "tool_name", "agent_name", "subagent_id", "reply_id"));
        if ("tool".equals(event.module())) {
            identity = identity + "|" + arguments(event);
        }
        return event.module() + "/" + event.action() + ":" + identity;
    }

    private static String arguments(TraceEvent event) {
        for (String name : TOOL_ARGUMENT_FIELDS) {
            Object value = event.fields().get(name);
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                return Json.canonical(value);
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static String field(TraceEvent event, List<String> names) {
        for (String name : names) {
            Object value = event.fields().get(name);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static List<String> words(String text) {
        String[] raw = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        List<String> words = new ArrayList<>();
        for (String word : raw) {
            if (!word.isBlank()) {
                words.add(word);
            }
            if (words.size() == MAX_WORDS) {
                break;
            }
        }
        return words;
    }

    private static double similarity(List<String> left, List<String> right) {
        return Math.max(bigramJaccard(left, right), lcsRatio(left, right));
    }

    private static double bigramJaccard(List<String> left, List<String> right) {
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> bigrams(List<String> words) {
        Set<String> grams = new HashSet<>();
        for (int index = 0; index + 1 < words.size(); index++) {
            grams.add(words.get(index) + ' ' + words.get(index + 1));
        }
        return grams;
    }

    private static double lcsRatio(List<String> left, List<String> right) {
        int[][] lengths = new int[left.size() + 1][right.size() + 1];
        for (int i = 1; i <= left.size(); i++) {
            for (int j = 1; j <= right.size(); j++) {
                lengths[i][j] = left.get(i - 1).equals(right.get(j - 1))
                        ? lengths[i - 1][j - 1] + 1
                        : Math.max(lengths[i - 1][j], lengths[i][j - 1]);
            }
        }
        return (2.0 * lengths[left.size()][right.size()]) / (left.size() + right.size());
    }

    private static Map<String, Object> evidenceRow(String signal, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("signal", signal);
        row.put("detail", detail);
        return row;
    }

    private record ToolSignal(
            boolean observed, double score, int calls, int maxRepetition, Set<String> repeatedTools) {
    }

    private record StallSignal(boolean observed, double score, int comparedOutputs, double maxSimilarity) {
    }
}
