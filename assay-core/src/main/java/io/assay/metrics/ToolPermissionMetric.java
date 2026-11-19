package io.assay.metrics;

import io.assay.model.MetricResult;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Scores whether the agent called only tools it was allowed to call.
 *
 * <p>The metric is deterministic and needs no model. It checks two sources: tool calls in the trace
 * (matched against an allow list and a deny list) and explicit {@code all_tools_denied} events.
 * A case that never calls a tool is skipped rather than scored 1.0 — an empty trace must not look
 * like a perfect permission record.
 */
public final class ToolPermissionMetric implements Metric {

    /** Stable metric name. */
    public static final String NAME = "tool_permission";

    private static final List<String> TOOL_NAME_FIELDS = List.of("tool_call_name", "tool_name", "name", "tool");
    private static final String DENIED_ACTION = "all_tools_denied";

    private final Set<String> allowedTools;
    private final Set<String> deniedTools;
    private final double threshold;

    /** Measures explicit denials only. */
    public ToolPermissionMetric() {
        this(List.of(), List.of());
    }

    /** Measures the deny list, or the allow list when one is supplied, at a threshold of 1.0. */
    public ToolPermissionMetric(Collection<String> allowedTools, Collection<String> deniedTools) {
        this(allowedTools, deniedTools, 1.0);
    }

    /**
     * Creates a permission metric.
     *
     * @param allowedTools tools the agent may call; empty means "no allow list"
     * @param deniedTools tools the agent must never call
     * @param threshold minimum score required to pass, between 0 and 1
     */
    public ToolPermissionMetric(Collection<String> allowedTools, Collection<String> deniedTools, double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("tool_permission threshold must be finite and between 0 and 1");
        }
        this.allowedTools = clean(allowedTools);
        this.deniedTools = clean(deniedTools);
        Set<String> overlap = new TreeSet<>(this.allowedTools);
        overlap.retainAll(this.deniedTools);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("tool appears in both allow and deny lists: " + overlap);
        }
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String defaultLevel() {
        return "hard";
    }

    @Override
    public String fingerprint() {
        return "v1;threshold=" + threshold + ";allow=" + allowedTools + ";deny=" + deniedTools;
    }

    @Override
    public MetricResult measure(MetricContext context) {
        NormalizedTrace trace = context.trace();
        if (trace == null) {
            return MetricResult.skip(NAME, threshold, "no trace");
        }

        int toolCalls = 0;
        int deniedEvents = 0;
        int violations = 0;
        int unnamedCalls = 0;
        Set<String> violatingTools = new LinkedHashSet<>();
        List<Map<String, Object>> evidence = new ArrayList<>();

        for (TraceEvent event : trace.events()) {
            if (DENIED_ACTION.equals(event.action())) {
                deniedEvents++;
                violations++;
                violatingTools.add(DENIED_ACTION);
                evidence.add(row(DENIED_ACTION, "runtime denied all tool calls"));
                continue;
            }
            if (!"tool".equals(event.module()) || !"call_start".equals(event.action())) {
                continue;
            }
            toolCalls++;
            String tool = toolName(event);
            if (tool.isEmpty()) {
                unnamedCalls++;
                if (!allowedTools.isEmpty()) {
                    violations++;
                    violatingTools.add("(unnamed)");
                    evidence.add(row("(unnamed)", "unnamed tool call cannot be checked against the allow list"));
                }
                continue;
            }
            if (deniedTools.contains(tool)) {
                violations++;
                violatingTools.add(tool);
                evidence.add(row(tool, "tool is explicitly denied"));
            } else if (!allowedTools.isEmpty() && !allowedTools.contains(tool)) {
                violations++;
                violatingTools.add(tool);
                evidence.add(row(tool, "tool is not in the allow list"));
            }
        }

        int attempts = toolCalls + deniedEvents;
        if (attempts == 0) {
            return MetricResult.skip(NAME, threshold, "no tool calls");
        }
        int authorized = Math.max(0, attempts - violations);
        double score = Math.max(0.0, Math.min(1.0, (double) authorized / attempts));

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("tool_calls", toolCalls);
        breakdown.put("denied_events", deniedEvents);
        breakdown.put("violations", violations);
        breakdown.put("unnamed_calls", unnamedCalls);
        breakdown.put("authorized", authorized);
        breakdown.put("allowed_tools", new ArrayList<>(allowedTools));
        breakdown.put("denied_tools", new ArrayList<>(deniedTools));

        String reason = reason(authorized, attempts, violatingTools);
        MetricResult result = score >= threshold
                ? MetricResult.pass(NAME, score, threshold, reason)
                : MetricResult.fail(NAME, score, threshold, reason);
        return result.withDetails(breakdown, evidence);
    }

    private static String reason(int authorized, int attempts, Set<String> violatingTools) {
        if (violatingTools.isEmpty()) {
            return "all " + attempts + " tool calls authorized";
        }
        return "authorized " + authorized + "/" + attempts + " tool calls; violating: "
                + String.join(", ", violatingTools);
    }

    private static String toolName(TraceEvent event) {
        for (String field : TOOL_NAME_FIELDS) {
            Object value = event.fields().get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static Map<String, Object> row(String tool, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tool", tool);
        row.put("reason", reason);
        return row;
    }

    private static Set<String> clean(Collection<String> values) {
        Set<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
