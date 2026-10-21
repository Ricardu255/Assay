package io.assay.evolve;

import io.assay.rules.ValueOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic step-level comparison of a baseline and a candidate trace.
 *
 * <p>The comparison is evidence for a human, not a gate: a non-deterministic agent may legitimately
 * take a different path, so this class never decides a verdict. It aligns steps by
 * {@code module/action} with a longest-common-subsequence pass, reports the first visible difference,
 * and ignores fields that are expected to vary between runs. Without that ignore list every event
 * would differ on its span id alone.
 *
 * <p>The public result is JSON-shaped so it can be stored in {@code evolution.json} and rendered by
 * the dashboard without another model type.
 */
public final class TraceDiff {

    /** Above this size the quadratic alignment is replaced by a positional comparison. */
    private static final int MAX_ALIGNED_STEPS = 300;

    /** How many divergences after the first one are kept in the result. */
    private static final int MAX_FOLLOWING_DIVERGENCES = 20;

    /** How many leaf paths one field comparison reports before truncating. */
    private static final int MAX_CHANGED_PATHS = 12;

    /**
     * Volatile fields that say when or where a step ran, not what it did. Timing stays visible in the
     * run's metrics; comparing it here would make every step look different.
     */
    private static final Set<String> DEFAULT_IGNORED_FIELDS = Set.of(
            "span_id", "parent_span_id", "start_time", "end_time",
            "start_time_ms", "end_time_ms", "timestamp", "duration_ms", "latency_ms");

    private TraceDiff() {
    }

    /**
     * Compares two trace projections.
     *
     * @param baselineTrace the baseline trace, or an empty map when the case produced no trace
     * @param candidateTrace the candidate trace, or an empty map when the case produced no trace
     * @param caseMetadata the case metadata; {@code trace_diff_ignore} lists extra dotted field paths
     * @return a JSON-shaped diagnosis with {@code alignment}, step counts, {@code divergence_count},
     *     {@code first_divergence}, and the following {@code divergences}
     */
    public static Map<String, Object> compare(
            Map<String, Object> baselineTrace,
            Map<String, Object> candidateTrace,
            Map<String, Object> caseMetadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean baselineMissing = baselineTrace == null || baselineTrace.isEmpty();
        boolean candidateMissing = candidateTrace == null || candidateTrace.isEmpty();
        List<Step> baseline = baselineMissing ? List.of() : steps(baselineTrace);
        List<Step> candidate = candidateMissing ? List.of() : steps(candidateTrace);
        result.put("baseline_steps", baseline.size());
        result.put("candidate_steps", candidate.size());

        if (baselineMissing || candidateMissing) {
            String reason = baselineMissing && candidateMissing
                    ? "both baseline and candidate produced no readable trace"
                    : baselineMissing
                            ? "baseline produced no readable trace"
                            : "candidate produced no readable trace";
            result.put("alignment", "none");
            result.put("divergence_count", 0);
            result.put("first_divergence", new Divergence(
                            null, null, "missing_trace", reason, null, null,
                            List.of(), List.of(), List.of(), false)
                    .toJson(true));
            result.put("divergences", List.of());
            return result;
        }

        Set<String> ignored = ignoredFields(caseMetadata);
        Alignment alignment = align(baseline, candidate);
        result.put("alignment", alignment.name());

        List<Divergence> divergences = new ArrayList<>();
        int baselineIndex = 0;
        int candidateIndex = 0;
        for (int[] match : alignment.matches()) {
            compareGap(
                    baseline, candidate,
                    baselineIndex, match[0],
                    candidateIndex, match[1],
                    divergences);
            Divergence paired = comparePair(baseline.get(match[0]), candidate.get(match[1]), ignored);
            if (paired != null) {
                divergences.add(paired);
            }
            baselineIndex = match[0] + 1;
            candidateIndex = match[1] + 1;
        }
        compareGap(
                baseline, candidate,
                baselineIndex, baseline.size(),
                candidateIndex, candidate.size(),
                divergences);
        divergences.sort(Comparator
                .comparingInt((Divergence divergence) -> position(divergence.candidateStep()))
                .thenComparingInt(divergence -> position(divergence.baselineStep())));

        if (divergences.isEmpty()) {
            Divergence terminal = terminalDivergence(baselineTrace, candidateTrace, ignored);
            result.put("divergence_count", "no_visible_divergence".equals(terminal.category()) ? 0 : 1);
            result.put("first_divergence", terminal.toJson(true));
            result.put("divergences", List.of());
            return result;
        }

        result.put("divergence_count", divergences.size());
        result.put("first_divergence", divergences.get(0).toJson(true));
        List<Map<String, Object>> following = new ArrayList<>();
        for (int index = 1; index < divergences.size() && following.size() < MAX_FOLLOWING_DIVERGENCES; index++) {
            following.add(divergences.get(index).toJson(false));
        }
        result.put("divergences", following);
        result.put("divergences_truncated", divergences.size() - 1 > following.size());
        return result;
    }

    private static int position(Integer step) {
        return step == null ? Integer.MAX_VALUE : step;
    }

    /** One step read out of a trace's event list. */
    private record Step(int number, Map<String, Object> event, String module, String action, String key) {
    }

    /** An alignment strategy plus the index pairs it matched. */
    private record Alignment(String name, List<int[]> matches) {
    }

    /**
     * Aligns two step sequences by their {@code module/action} keys.
     *
     * <p>LCS keeps an inserted or removed step local instead of shifting every later step. A trace
     * longer than {@link #MAX_ALIGNED_STEPS} falls back to a positional comparison, which is cheaper
     * but reports every step after an insertion; the result says so in {@code alignment}.
     */
    private static Alignment align(List<Step> baseline, List<Step> candidate) {
        int baselineSize = baseline.size();
        int candidateSize = candidate.size();
        if (baselineSize > MAX_ALIGNED_STEPS || candidateSize > MAX_ALIGNED_STEPS) {
            List<int[]> matches = new ArrayList<>();
            for (int index = 0; index < Math.min(baselineSize, candidateSize); index++) {
                matches.add(new int[] {index, index});
            }
            return new Alignment("positional", matches);
        }

        int[][] longest = new int[baselineSize + 1][candidateSize + 1];
        for (int left = baselineSize - 1; left >= 0; left--) {
            for (int right = candidateSize - 1; right >= 0; right--) {
                longest[left][right] = baseline.get(left).key().equals(candidate.get(right).key())
                        ? longest[left + 1][right + 1] + 1
                        : Math.max(longest[left + 1][right], longest[left][right + 1]);
            }
        }

        List<int[]> matches = new ArrayList<>();
        int left = 0;
        int right = 0;
        while (left < baselineSize && right < candidateSize) {
            if (baseline.get(left).key().equals(candidate.get(right).key())) {
                matches.add(new int[] {left, right});
                left++;
                right++;
            } else if (longest[left + 1][right] >= longest[left][right + 1]) {
                left++;
            } else {
                right++;
            }
        }
        return new Alignment("lcs", matches);
    }

    /**
     * Compares one unmatched region between two anchors: equal-length leftovers are a replacement,
     * and the remainder is an addition or a removal.
     */
    private static void compareGap(
            List<Step> baseline,
            List<Step> candidate,
            int baselineStart,
            int baselineEnd,
            int candidateStart,
            int candidateEnd,
            List<Divergence> divergences) {
        int baselineCount = baselineEnd - baselineStart;
        int candidateCount = candidateEnd - candidateStart;
        int paired = Math.min(baselineCount, candidateCount);
        for (int offset = 0; offset < paired; offset++) {
            Step baselineStep = baseline.get(baselineStart + offset);
            Step candidateStep = candidate.get(candidateStart + offset);
            boolean sameStep = baselineStep.key().equals(candidateStep.key());
            String category = sameStep ? "step_reordered" : "step_changed";
            String reason = sameStep
                    ? "candidate moved " + label(baselineStep) + " from position " + baselineStep.number()
                            + " to " + candidateStep.number()
                    : "candidate changed step " + candidateStep.number() + " from " + label(baselineStep)
                            + " to " + label(candidateStep);
            divergences.add(new Divergence(
                    baselineStep.number(), candidateStep.number(), category, reason,
                    baselineStep.event(), candidateStep.event(),
                    List.of(), List.of(), List.of(), false));
        }
        for (int offset = paired; offset < baselineCount; offset++) {
            Step baselineStep = baseline.get(baselineStart + offset);
            // The removed step is missing at the candidate position that follows the replaced pair.
            int candidatePosition = Math.min(candidateStart + paired + 1, candidate.size() + 1);
            divergences.add(new Divergence(
                    baselineStep.number(), candidatePosition, "step_removed",
                    "candidate removed step " + baselineStep.number() + " " + label(baselineStep),
                    baselineStep.event(), null,
                    List.of(), List.of(), List.of(), false));
        }
        for (int offset = paired; offset < candidateCount; offset++) {
            Step candidateStep = candidate.get(candidateStart + offset);
            divergences.add(new Divergence(
                    null, candidateStep.number(), "step_added",
                    "candidate added step " + candidateStep.number() + " " + label(candidateStep),
                    null, candidateStep.event(),
                    List.of(), List.of(), List.of(), false));
        }
    }

    /** Compares two steps with the same key: error first, then status, then recorded fields. */
    private static Divergence comparePair(Step baseline, Step candidate, Set<String> ignored) {
        if (!baseline.key().equals(candidate.key())) {
            return new Divergence(
                    baseline.number(), candidate.number(), "step_changed",
                    "candidate changed step " + candidate.number() + " from " + label(baseline)
                            + " to " + label(candidate),
                    baseline.event(), candidate.event(),
                    List.of(), List.of(), List.of(), false);
        }

        String baselineError = errorText(baseline.event());
        String candidateError = errorText(candidate.event());
        if (!baselineError.equals(candidateError)) {
            String reason;
            if (baselineError.isEmpty()) {
                reason = "candidate step " + candidate.number() + " failed: " + candidateError;
            } else if (candidateError.isEmpty()) {
                reason = "candidate step " + candidate.number() + " no longer reports the baseline error";
            } else {
                reason = "candidate step " + candidate.number() + " error changed from " + baselineError
                        + " to " + candidateError;
            }
            return new Divergence(
                    baseline.number(), candidate.number(), "step_error", reason,
                    baseline.event(), candidate.event(),
                    List.of(), List.of(), List.of(), false);
        }

        String baselineStatus = status(baseline.event());
        String candidateStatus = status(candidate.event());
        if (!baselineStatus.equals(candidateStatus)) {
            return new Divergence(
                    baseline.number(), candidate.number(), "step_status",
                    "candidate step " + candidate.number() + " status changed from " + baselineStatus
                            + " to " + candidateStatus,
                    baseline.event(), candidate.event(),
                    List.of(), List.of(), List.of(), false);
        }

        Diff diff = diffFields(
                asMap(baseline.event().get("fields")), asMap(candidate.event().get("fields")), ignored);
        if (!diff.paths.isEmpty()) {
            return new Divergence(
                    baseline.number(), candidate.number(), "step_fields",
                    "candidate step " + candidate.number() + " " + label(candidate)
                            + " fields." + diff.paths.get(0) + " changed",
                    baseline.event(), candidate.event(),
                    diff.paths, diff.baseline, diff.candidate, diff.truncated);
        }
        return null;
    }

    /** When the steps match, the failure is in the answer itself or in a trace-level field. */
    private static Divergence terminalDivergence(
            Map<String, Object> baseline, Map<String, Object> candidate, Set<String> ignored) {
        Object baselineOutput = baseline.get("final_output");
        Object candidateOutput = candidate.get("final_output");
        if (!ValueOps.equal(baselineOutput, candidateOutput)) {
            return new Divergence(
                    null, null, "final_output", "final output changed",
                    null, null, List.of("final_output"),
                    values(baselineOutput), values(candidateOutput), false);
        }

        Diff diff = diffFields(asMap(baseline.get("fields")), asMap(candidate.get("fields")), ignored);
        if (!diff.paths.isEmpty()) {
            return new Divergence(
                    null, null, "trace_fields",
                    "trace fields." + diff.paths.get(0) + " changed",
                    null, null, diff.paths, diff.baseline, diff.candidate, diff.truncated);
        }

        return new Divergence(
                null, null, "no_visible_divergence",
                "steps, final output, and trace fields match; the failure comes from a rule on identical data "
                        + "or from run-level integrity",
                null, null, List.of(), List.of(), List.of(), false);
    }

    private static List<Step> steps(Map<String, Object> trace) {
        List<?> events = asList(trace.get("events"));
        List<Step> steps = new ArrayList<>(events.size());
        for (int index = 0; index < events.size(); index++) {
            Map<String, Object> event = asMap(events.get(index));
            String module = text(event.get("module"));
            String action = text(event.get("action"));
            steps.add(new Step(index + 1, event, module, action, module + '\u0000' + action));
        }
        return steps;
    }

    /** Adds a leading {@code trace_diff_ignore} list to the volatile-field defaults. */
    private static Set<String> ignoredFields(Map<String, Object> metadata) {
        Set<String> ignored = new LinkedHashSet<>(DEFAULT_IGNORED_FIELDS);
        Object raw = metadata == null ? null : metadata.get("trace_diff_ignore");
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                addIgnored(ignored, item);
            }
        } else if (raw instanceof String text) {
            for (String item : text.split(",")) {
                addIgnored(ignored, item);
            }
        }
        return ignored;
    }

    private static void addIgnored(Set<String> ignored, Object value) {
        if (value == null) {
            return;
        }
        String path = String.valueOf(value).trim();
        if (!path.isEmpty()) {
            ignored.add(path);
        }
    }

    private static boolean ignored(String path, Set<String> ignored) {
        for (String entry : ignored) {
            if (path.equals(entry) || path.startsWith(entry + ".")) {
                return true;
            }
        }
        return false;
    }

    private static Diff diffFields(Object baseline, Object candidate, Set<String> ignored) {
        Diff diff = new Diff();
        collect(baseline, candidate, "", ignored, diff);
        return diff;
    }

    /**
     * Collects leaf paths that differ. Maps compare by key union, lists by index, and scalars through
     * the same equality the rule engine uses, so numbers written as {@code 1} and {@code 1.0} agree.
     */
    private static void collect(
            Object baseline, Object candidate, String path, Set<String> ignored, Diff diff) {
        if (diff.paths.size() >= MAX_CHANGED_PATHS) {
            diff.truncated = true;
            return;
        }
        if (!path.isEmpty() && ignored(path, ignored)) {
            return;
        }

        if (baseline instanceof Map<?, ?> && candidate instanceof Map<?, ?>) {
            Map<String, Object> left = asMap(baseline);
            Map<String, Object> right = asMap(candidate);
            Set<String> keys = new TreeSet<>();
            keys.addAll(left.keySet());
            keys.addAll(right.keySet());
            for (String key : keys) {
                String child = path.isEmpty() ? key : path + "." + key;
                if (ignored(child, ignored)) {
                    continue;
                }
                boolean hasLeft = left.containsKey(key);
                boolean hasRight = right.containsKey(key);
                if (!hasLeft || !hasRight) {
                    diff.add(child, hasLeft ? left.get(key) : null, hasRight ? right.get(key) : null);
                } else {
                    collect(left.get(key), right.get(key), child, ignored, diff);
                }
                if (diff.paths.size() >= MAX_CHANGED_PATHS) {
                    diff.truncated = true;
                    return;
                }
            }
            return;
        }

        if (baseline instanceof List<?> && candidate instanceof List<?>) {
            List<?> left = (List<?>) baseline;
            List<?> right = (List<?>) candidate;
            int length = Math.max(left.size(), right.size());
            for (int index = 0; index < length; index++) {
                String child = path.isEmpty() ? String.valueOf(index) : path + "." + index;
                if (ignored(child, ignored)) {
                    continue;
                }
                Object leftValue = index < left.size() ? left.get(index) : null;
                Object rightValue = index < right.size() ? right.get(index) : null;
                if (index >= left.size() || index >= right.size()) {
                    diff.add(child, leftValue, rightValue);
                } else {
                    collect(leftValue, rightValue, child, ignored, diff);
                }
                if (diff.paths.size() >= MAX_CHANGED_PATHS) {
                    diff.truncated = true;
                    return;
                }
            }
            return;
        }

        if (!ValueOps.equal(baseline, candidate)) {
            diff.add(path.isEmpty() ? "(root)" : path, baseline, candidate);
        }
    }

    private static String errorText(Map<String, Object> event) {
        Object error = event.get("error");
        if (error != null && !String.valueOf(error).isBlank()) {
            return String.valueOf(error);
        }
        String status = status(event);
        return "error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status) ? status : "";
    }

    private static String status(Map<String, Object> event) {
        Object value = event.get("status");
        return value == null || String.valueOf(value).isBlank() ? "ok" : String.valueOf(value);
    }

    private static String label(Step step) {
        if (!step.module().isEmpty() && !step.action().isEmpty()) {
            return step.module() + "/" + step.action();
        }
        if (!step.action().isEmpty()) {
            return step.action();
        }
        if (!step.module().isEmpty()) {
            return step.module();
        }
        return "(unnamed step)";
    }

    private static List<Object> values(Object value) {
        List<Object> values = new ArrayList<>(1);
        values.add(value);
        return values;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    /** Mutable accumulator for one field comparison. */
    private static final class Diff {
        private final List<String> paths = new ArrayList<>();
        private final List<Object> baseline = new ArrayList<>();
        private final List<Object> candidate = new ArrayList<>();
        private boolean truncated;

        private void add(String path, Object left, Object right) {
            if (paths.size() >= MAX_CHANGED_PATHS) {
                truncated = true;
                return;
            }
            paths.add(path);
            baseline.add(left);
            candidate.add(right);
        }
    }

    /** One visible difference, with enough context to explain it without re-reading both traces. */
    private record Divergence(
            Integer baselineStep,
            Integer candidateStep,
            String category,
            String reason,
            Map<String, Object> baselineEvent,
            Map<String, Object> candidateEvent,
            List<String> changedPaths,
            List<Object> baselineValues,
            List<Object> candidateValues,
            boolean truncated) {

        private Integer step() {
            return candidateStep != null ? candidateStep : baselineStep;
        }

        private Map<String, Object> toJson(boolean includeFields) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("category", category);
            result.put("step", step());
            result.put("baseline_step", baselineStep);
            result.put("candidate_step", candidateStep);
            result.put("reason", reason);
            result.put("changed_paths", changedPaths);
            result.put("baseline_values", baselineValues);
            result.put("candidate_values", candidateValues);
            result.put("changed_paths_truncated", truncated);
            if (baselineEvent != null) {
                result.put("baseline", eventJson(baselineEvent, includeFields));
            }
            if (candidateEvent != null) {
                result.put("candidate", eventJson(candidateEvent, includeFields));
            }
            return result;
        }

        private static Map<String, Object> eventJson(Map<String, Object> event, boolean includeFields) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("module", text(event.get("module")));
            result.put("action", text(event.get("action")));
            result.put("status", text(event.get("status")));
            result.put("error", event.get("error"));
            result.put("duration_ms", event.get("duration_ms"));
            if (includeFields) {
                result.put("fields", asMap(event.get("fields")));
            }
            return result;
        }
    }
}
